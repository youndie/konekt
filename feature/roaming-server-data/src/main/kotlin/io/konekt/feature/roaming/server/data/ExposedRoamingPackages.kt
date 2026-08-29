package io.konekt.feature.roaming.server.data

import io.konekt.feature.roaming.server.domain.RoamingConsumption
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.roaming.server.domain.Travelling
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExposedRoamingPackages(
    private val db: Database,
    private val clock: KonektClock,
) : RoamingPackages {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun grant(
        orderId: String,
        subscriberId: String,
        zone: String,
        limitMb: Long,
        validForDays: Long,
        purchasedAt: Instant,
    ) {
        dbQuery {
            // insertIgnore rather than insert: a saga step that runs twice must grant one package,
            // and the unique constraint on order_id turns the second attempt into a no-op instead of
            // an exception that rolls a completed purchase back.
            RoamingPackageTable.insertIgnore {
                it[RoamingPackageTable.id] = Uuid.random().toString()
                it[RoamingPackageTable.orderId] = orderId
                it[RoamingPackageTable.subscriberId] = subscriberId
                it[RoamingPackageTable.zone] = zone
                it[RoamingPackageTable.limitMb] = limitMb
                it[RoamingPackageTable.remainingMb] = limitMb
                it[RoamingPackageTable.validForDays] = validForDays
                it[RoamingPackageTable.purchasedAt] = purchasedAt.toEpochMilliseconds()
                // The two columns the feature is about, and they start empty. There is no code path
                // in this class that writes them at grant time.
                it[RoamingPackageTable.activatedAt] = null
                it[RoamingPackageTable.expiresAt] = null
            }
        }
    }

    override suspend fun revoke(orderId: String) {
        dbQuery {
            // ONLY WHILE IT IS STILL DORMANT. A package the subscriber has already started using is
            // not something a compensation may delete: the bytes are spent, the trip is happening,
            // and removing the row would make a screen that said "4 GB left" say nothing at all.
            //
            // In the saga this is a distinction without a difference — the compensation runs seconds
            // after the grant, long before anyone lands. It is here because a repository that can
            // silently erase used data is one edit away from being called from somewhere else.
            RoamingPackageTable.deleteWhere {
                (RoamingPackageTable.orderId eq orderId) and (RoamingPackageTable.activatedAt eq null)
            }
        }
    }

    override suspend fun of(subscriberId: String): List<RoamingPackage> =
        dbQuery {
            RoamingPackageTable
                .selectAll()
                .where { RoamingPackageTable.subscriberId eq subscriberId }
                .map { it.toDomain() }
                // Oldest purchase first, which is also the order they are spent in. One ordering for
                // both means the screen lists them in the sequence they will actually be used.
                .sortedBy { it.purchasedAt }
        }

    override suspend fun travelling(): List<Travelling> =
        dbQuery {
            RoamingPackageTable
                .selectAll()
                // STARTED, in SQL rather than in Kotlin, because this is the one query here that runs
                // over every subscriber rather than one. The expiry and the remainder are filtered in
                // Kotlin below against a single `now`, so every row in one call is judged against the
                // same instant.
                .where { RoamingPackageTable.activatedAt.isNotNull() }
                .map { it.toDomain() }
                .filter { it.usableAt(clock.now()) }
                .map { Travelling(it.subscriberId, it.zone) }
                // One tick per subscriber and zone. Two live packages for the same zone are spent one
                // after the other, so ticking twice would spend them twice as fast for no reason.
                .distinct()
        }

    override suspend fun awaitingArrival(purchasedBefore: Instant): List<Travelling> =
        dbQuery {
            RoamingPackageTable
                .selectAll()
                // DORMANT AND OLD ENOUGH, both in SQL: this runs over every subscriber like
                // `travelling` above, and both halves are columns.
                .where {
                    RoamingPackageTable.activatedAt.isNull() and
                        (RoamingPackageTable.purchasedAt lessEq purchasedBefore.toEpochMilliseconds())
                }.map { it.toDomain() }
                // A package with nothing in it has nothing to start. It cannot be bought that way —
                // the catalogue has no zero-megabyte plan — and the filter costs a comparison rather
                // than a reader wondering.
                .filter { it.remainingMb > 0 }
                .map { Travelling(it.subscriberId, it.zone) }
                // One arrival per subscriber and zone, the same reason `travelling` is distinct: two
                // packages for one zone are spent one after the other, and two arrival events would
                // start one and waste the other's first megabyte.
                .distinct()
        }

    override suspend fun consume(
        subscriberId: String,
        zone: String,
        megabytes: Long,
        at: Instant,
    ): RoamingConsumption =
        dbQuery {
            val candidates =
                RoamingPackageTable
                    .selectAll()
                    .where {
                        (RoamingPackageTable.subscriberId eq subscriberId) and (RoamingPackageTable.zone eq zone)
                    }.map { it.toDomain() }
                    .filter { it.usableAt(at) }

            // ALREADY-STARTED PACKAGES FIRST, and only then a dormant one. Spending a fresh package
            // while a running one still has data left would start a second clock for no reason and
            // strand the remainder of the first — the subscriber paid for both, and they should run
            // one after the other rather than in parallel.
            val pkg =
                candidates.firstOrNull { !it.dormant }
                    ?: candidates.minByOrNull { it.purchasedAt }
                    ?: return@dbQuery RoamingConsumption.NoPackage

            val starting = pkg.dormant
            // Clamped BEFORE the subtraction, not after. The counters in this codebase learned this
            // the expensive way: `remaining - units` written unconditionally turns a 10 MB request
            // against 3 MB left into minus seven, and a screen that says minus seven.
            val taken = minOf(megabytes, pkg.remainingMb)

            RoamingPackageTable.update({ RoamingPackageTable.id eq pkg.id }) {
                it[remainingMb] = RoamingPackageTable.remainingMb plus (-taken)
                if (starting) {
                    // THE ACTIVATION, and the only place in the codebase that writes these two
                    // columns. `at` is the moment of the first byte, and the expiry is computed from
                    // it — which is the whole of the second acceptance criterion.
                    it[activatedAt] = at.toEpochMilliseconds()
                    it[expiresAt] = pkg.expiryIfActivatedAt(at).toEpochMilliseconds()
                }
            }

            RoamingConsumption.Counted(
                pkg =
                    pkg.copy(
                        remainingMb = pkg.remainingMb - taken,
                        activatedAt = if (starting) at else pkg.activatedAt,
                        expiresAt = if (starting) pkg.expiryIfActivatedAt(at) else pkg.expiresAt,
                    ),
                consumedMb = taken,
                started = starting,
            )
        }

    private fun ResultRow.toDomain() =
        RoamingPackage(
            id = this[RoamingPackageTable.id],
            orderId = this[RoamingPackageTable.orderId],
            subscriberId = this[RoamingPackageTable.subscriberId],
            zone = this[RoamingPackageTable.zone],
            limitMb = this[RoamingPackageTable.limitMb],
            remainingMb = this[RoamingPackageTable.remainingMb],
            validForDays = this[RoamingPackageTable.validForDays],
            purchasedAt = Instant.fromEpochMilliseconds(this[RoamingPackageTable.purchasedAt]),
            activatedAt = this[RoamingPackageTable.activatedAt]?.let(Instant::fromEpochMilliseconds),
            expiresAt = this[RoamingPackageTable.expiresAt]?.let(Instant::fromEpochMilliseconds),
        )
}
