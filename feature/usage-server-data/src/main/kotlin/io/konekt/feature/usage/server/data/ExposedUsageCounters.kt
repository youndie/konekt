package io.konekt.feature.usage.server.data

import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.feature.usage.server.domain.UsageCounters
import io.konekt.feature.usage.server.domain.UsageGrants
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExposedUsageCounters(
    private val db: Database,
    private val clock: KonektClock,
) : UsageCounters,
    UsageGrants {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun of(subscriberId: String): List<UsageCounter> =
        dbQuery {
            UsageCounterTable
                .selectAll()
                .where { UsageCounterTable.subscriberId eq subscriberId }
                .map { it.toDomain() }
                // Ordered by the enum rather than by the database, so two screens never disagree
                // about which counter comes first.
                .sortedBy { it.kind.ordinal }
        }

    override suspend fun subscribersWithCounters(): List<String> =
        dbQuery {
            UsageCounterTable
                .selectAll()
                .map { row -> row[UsageCounterTable.subscriberId] }
                // Distinct in Kotlin rather than in SQL: a subscriber has at most three rows here,
                // so the set is the same size either way and a `withDistinct()` would be a query
                // shape to maintain for nothing.
                .distinct()
        }

    override suspend fun find(
        subscriberId: String,
        kind: UsageCounter.Kind,
    ): UsageCounter? =
        dbQuery {
            UsageCounterTable
                .selectAll()
                .where {
                    (UsageCounterTable.subscriberId eq subscriberId) and (UsageCounterTable.kind eq kind.wireName)
                }.singleOrNull()
                ?.toDomain()
        }

    override suspend fun grant(
        subscriberId: String,
        kind: UsageCounter.Kind,
        units: Long,
    ) {
        dbQuery {
            // An upsert that ADDS on conflict rather than replacing. A second purchase of the same
            // plan tops the allowance up; replacing would silently throw away whatever was left of
            // the first, which is the subscriber's money.
            UsageCounterTable.upsert(
                UsageCounterTable.subscriberId,
                UsageCounterTable.kind,
                onUpdate = {
                    it[UsageCounterTable.limitUnits] = UsageCounterTable.limitUnits plus units
                    it[UsageCounterTable.remainingUnits] = UsageCounterTable.remainingUnits plus units
                },
            ) {
                it[id] = Uuid.random().toString()
                it[UsageCounterTable.subscriberId] = subscriberId
                it[UsageCounterTable.kind] = kind.wireName
                it[limitUnits] = units
                it[remainingUnits] = units
                it[createdAt] = clock.now().toEpochMilliseconds()
            }
        }
    }

    override suspend fun consume(
        subscriberId: String,
        kind: UsageCounter.Kind,
        units: Long,
    ): UsageCounter? =
        dbQuery {
            // TWO UPDATES rather than one clever expression, because the clamp has to happen in SQL:
            // `remaining = remaining - units` alone goes negative under a burst, and a screen that
            // says minus four hundred megabytes is worse than one that says zero. A CHECK constraint
            // would refuse the write instead, leaving the caller to handle a failure that has an
            // obvious right answer.
            //
            // THE CLAMP GOES FIRST, AND THAT ORDER IS THE WHOLE CORRECTNESS. Written the other way
            // round — subtract, then clamp — the two are not mutually exclusive at all: the subtract
            // changes the row, and the clamp's predicate then reads the NEW value. Consuming 950 of
            // 1000 left 50 and the clamp immediately zeroed it, because 50 < 950. Every consumption
            // taking more than half of what was left wiped the rest, silently, with no negative
            // number to notice and no error anywhere.
            //
            // In this order they really are exclusive: if the clamp fires the row is 0 and `0 >= units`
            // is false for any positive amount, and if it does not fire the subtract cannot go
            // negative. The old comment claimed exclusivity as a fact; it is a consequence of the
            // order, which is why the order is stated here rather than assumed.
            //
            // The predicates are also what make it safe under two consumers: the second of two
            // simultaneous decrements takes only what the first left, because Postgres re-evaluates
            // the predicate at READ COMMITTED.
            val enough =
                (UsageCounterTable.subscriberId eq subscriberId) and (UsageCounterTable.kind eq kind.wireName)

            UsageCounterTable.update({ enough and (UsageCounterTable.remainingUnits less units) }) {
                it[remainingUnits] = 0
            }

            UsageCounterTable.update({ enough and (UsageCounterTable.remainingUnits greaterEq units) }) {
                it[remainingUnits] = UsageCounterTable.remainingUnits plus (-units)
            }

            UsageCounterTable
                .selectAll()
                .where {
                    (UsageCounterTable.subscriberId eq subscriberId) and (UsageCounterTable.kind eq kind.wireName)
                }.singleOrNull()
                ?.toDomain()
        }

    override suspend fun grantPlanAllowance(
        subscriberId: String,
        dataMb: Long,
        minutes: Long,
        messages: Long,
    ) {
        // ZERO GRANTS NOTHING, and that is not a micro-optimisation. `grant` upserts, so granting
        // zero minutes would CREATE a minutes counter reading "0 of 0" — a subscriber who bought a
        // data package would find an empty allowance for calls on their home screen, which reads as
        // a plan that took their minutes away.
        if (dataMb > 0) grant(subscriberId, UsageCounter.Kind.DATA, dataMb)
        if (minutes > 0) grant(subscriberId, UsageCounter.Kind.MINUTES, minutes)
        if (messages > 0) grant(subscriberId, UsageCounter.Kind.MESSAGES, messages)
    }

    override suspend fun revokePlanAllowance(
        subscriberId: String,
        dataMb: Long,
        minutes: Long,
        messages: Long,
    ) {
        revokeOne(subscriberId, UsageCounter.Kind.DATA, dataMb)
        revokeOne(subscriberId, UsageCounter.Kind.MINUTES, minutes)
        revokeOne(subscriberId, UsageCounter.Kind.MESSAGES, messages)
    }

    // One kind's worth of taking back, so the three read identically rather than the first being
    // spelled out and the others bolted on beside it.
    private suspend fun revokeOne(
        subscriberId: String,
        kind: UsageCounter.Kind,
        units: Long,
    ) {
        if (units <= 0) return

        dbQuery {
            // Both columns, and both clamped in SQL. The limit falls because the allowance is gone;
            // the remainder falls because what is left of it is gone too — and it may already be
            // smaller than what was granted, which is why this is a `greatest(x, 0)` rather than a
            // subtraction.
            val row =
                (UsageCounterTable.subscriberId eq subscriberId) and
                    (UsageCounterTable.kind eq kind.wireName)

            // CLAMP FIRST, both columns, for the reason `consume` above spells out at length: written
            // subtract-then-clamp the pair is not exclusive, because the subtract changes the row the
            // clamp then reads. Revoking 1000 of a 1800 limit left 800 and the clamp zeroed it, since
            // 800 < 1000 — so a rolled-back purchase took away the subscriber's OTHER allowance too.
            //
            // Two columns and therefore four statements. They are not one pair repeated: the limit and
            // the remainder move independently, because the remainder may already be smaller than what
            // was granted.
            UsageCounterTable.update({ row and (UsageCounterTable.limitUnits less units) }) {
                it[limitUnits] = 0
            }
            UsageCounterTable.update({ row and (UsageCounterTable.limitUnits greaterEq units) }) {
                it[limitUnits] = UsageCounterTable.limitUnits plus (-units)
            }
            UsageCounterTable.update({ row and (UsageCounterTable.remainingUnits less units) }) {
                it[remainingUnits] = 0
            }
            UsageCounterTable.update({ row and (UsageCounterTable.remainingUnits greaterEq units) }) {
                it[remainingUnits] = UsageCounterTable.remainingUnits plus (-units)
            }
        }
    }

    private fun ResultRow.toDomain() =
        UsageCounter(
            subscriberId = this[UsageCounterTable.subscriberId],
            kind = UsageCounter.Kind.entries.first { it.wireName == this[UsageCounterTable.kind] },
            limitUnits = this[UsageCounterTable.limitUnits],
            remainingUnits = this[UsageCounterTable.remainingUnits],
            startedAt = Instant.fromEpochMilliseconds(this[UsageCounterTable.createdAt]),
        )
}
