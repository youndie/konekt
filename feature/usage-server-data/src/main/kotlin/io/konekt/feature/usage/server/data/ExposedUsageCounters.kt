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
            // TWO MUTUALLY EXCLUSIVE UPDATES rather than one clever expression, because the clamp
            // has to happen in SQL: `remaining = remaining - units` alone goes negative under a
            // burst, and a screen that says minus four hundred megabytes is worse than one that says
            // zero. A CHECK constraint would refuse the write instead, leaving the caller to handle a
            // failure that has an obvious right answer.
            //
            // The conditions are what make it safe under two consumers. Exactly one of the two
            // applies to any row, and the second of two simultaneous decrements takes only what the
            // first left, because Postgres re-evaluates the predicate at READ COMMITTED.
            val enough =
                (UsageCounterTable.subscriberId eq subscriberId) and (UsageCounterTable.kind eq kind.wireName)

            UsageCounterTable.update({ enough and (UsageCounterTable.remainingUnits greaterEq units) }) {
                it[remainingUnits] = UsageCounterTable.remainingUnits plus (-units)
            }

            UsageCounterTable.update({ enough and (UsageCounterTable.remainingUnits less units) }) {
                it[remainingUnits] = 0
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
    ) {
        grant(subscriberId, UsageCounter.Kind.DATA, dataMb)
    }

    override suspend fun revokePlanAllowance(
        subscriberId: String,
        dataMb: Long,
    ) {
        dbQuery {
            // Both columns, and both clamped in SQL. The limit falls because the allowance is gone;
            // the remainder falls because what is left of it is gone too — and it may already be
            // smaller than what was granted, which is why this is a `greatest(x, 0)` rather than a
            // subtraction.
            val row =
                (UsageCounterTable.subscriberId eq subscriberId) and
                    (UsageCounterTable.kind eq UsageCounter.Kind.DATA.wireName)

            UsageCounterTable.update({ row and (UsageCounterTable.limitUnits greaterEq dataMb) }) {
                it[limitUnits] = UsageCounterTable.limitUnits plus (-dataMb)
            }
            UsageCounterTable.update({ row and (UsageCounterTable.limitUnits less dataMb) }) {
                it[limitUnits] = 0
            }
            UsageCounterTable.update({ row and (UsageCounterTable.remainingUnits greaterEq dataMb) }) {
                it[remainingUnits] = UsageCounterTable.remainingUnits plus (-dataMb)
            }
            UsageCounterTable.update({ row and (UsageCounterTable.remainingUnits less dataMb) }) {
                it[remainingUnits] = 0
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
