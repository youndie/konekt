package io.konekt.tariff

import io.konekt.db.tables.TariffChangeTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Three tariffs, in memory. The BSS is outside this system's boundary; what matters for the
// demonstration is that they differ in price and in allowance, so a change is visibly a change.
class StaticTariffCatalogue : TariffCatalogue {
    private val tariffs =
        listOf(
            Tariff("tr-basic", "Basic", Money.ofMajor(5, Currency.DEFAULT), dataMb = 2 * MB_PER_GB),
            Tariff("tr-standard", "Standard", Money.ofMajor(12, Currency.DEFAULT), dataMb = 10 * MB_PER_GB),
            Tariff("tr-max", "Max", Money.ofMajor(25, Currency.DEFAULT), dataMb = 50 * MB_PER_GB),
        )

    private companion object {
        // WRITTEN AS AN ALLOWANCE TIMES A BASE, and the three numbers here used to be 2_000, 10_000
        // and 50_000 — a decimal thousand, which nothing else in this build uses.
        //
        // It cost nothing while no screen displayed them. `B-86` displayed them, and `UsageUnits`
        // divides by 1024 like everywhere else, so the catalogue offered "9.8 GB" for the tariff
        // called Standard and "48.8 GB" for Max. Neither is a rounding error a reader forgives on a
        // price list. The same base `StaticPlanCatalog` uses, named for the same reason: two figures
        // computed in two bases disagree with each other for a living.
        const val MB_PER_GB = 1_024L
    }

    override fun find(tariffId: String): Tariff? = tariffs.firstOrNull { it.id == tariffId }

    override fun all(): List<Tariff> = tariffs

    // What a subscriber is on when they have never changed. A property of the catalogue rather than a
    // column with a default: a deployment renaming its base tariff should not need a migration.
    override val default: Tariff get() = tariffs.first()
}

@OptIn(ExperimentalUuidApi::class)
class ExposedTariffChanges(
    private val db: Database,
    private val clock: KonektClock,
) : TariffChanges {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    // THE NEWEST APPLIED ROW WHOSE BOUNDARY HAS PASSED, and `null` when there is none.
    //
    // THE DATE FILTER IS THE FEATURE. Without it a confirmed change becomes the current tariff the
    // moment it is confirmed, which is exactly what "takes effect at the next billing boundary" is
    // not — and the first version of this had no filter, so a subscriber who confirmed on the
    // thirtieth was moved on the thirtieth. The saga's own test is what said so.
    //
    // A subscriber who never changed has no rows at all, which is why this migration needed no
    // backfill — and why the caller substitutes the catalogue's default rather than this doing it:
    // the log knows what happened, not what the catalogue calls the beginning.
    override suspend fun currentTariffId(subscriberId: String): String? =
        dbQuery {
            TariffChangeTable
                .selectAll()
                .where {
                    (TariffChangeTable.subscriberId eq subscriberId) and
                        (TariffChangeTable.status eq TariffChangeTable.APPLIED) and
                        (TariffChangeTable.effectiveAt lessEq clock.now().toEpochMilliseconds())
                }.orderBy(TariffChangeTable.effectiveAt, SortOrder.DESC)
                .firstOrNull()
                ?.get(TariffChangeTable.toTariffId)
        }

    override suspend fun pendingOf(subscriberId: String): TariffChangeRecord? =
        dbQuery {
            TariffChangeTable
                .selectAll()
                .where {
                    (TariffChangeTable.subscriberId eq subscriberId) and
                        (TariffChangeTable.status eq TariffChangeTable.PENDING)
                }.firstOrNull()
                ?.toRecord()
        }

    override suspend fun record(
        changeId: String,
        subscriberId: String,
        fromTariffId: String,
        toTariffId: String,
        effectiveAt: Instant,
    ) {
        dbQuery {
            TariffChangeTable.insert {
                it[id] = Uuid.random().toString()
                it[TariffChangeTable.changeId] = changeId
                it[TariffChangeTable.subscriberId] = subscriberId
                it[TariffChangeTable.fromTariffId] = fromTariffId
                it[TariffChangeTable.toTariffId] = toTariffId
                it[status] = TariffChangeTable.PENDING
                it[TariffChangeTable.effectiveAt] = effectiveAt.toEpochMilliseconds()
                it[createdAt] = clock.now().toEpochMilliseconds()
            }
        }
    }

    // ONLY FROM PENDING, which is the whole safety of it. A resumed saga and a sweeper can both reach
    // a change, and whichever arrives second must find nothing to do rather than undo the first.
    override suspend fun apply(changeId: String) {
        transition(changeId, from = TariffChangeTable.PENDING, to = TariffChangeTable.APPLIED)
    }

    override suspend fun cancel(changeId: String) {
        transition(changeId, from = TariffChangeTable.PENDING, to = TariffChangeTable.CANCELLED)
    }

    private suspend fun transition(
        changeId: String,
        from: String,
        to: String,
    ) {
        dbQuery {
            TariffChangeTable.update({
                (TariffChangeTable.changeId eq changeId) and (TariffChangeTable.status eq from)
            }) {
                it[status] = to
            }
        }
    }

    override suspend fun findByChange(changeId: String): TariffChangeRecord? =
        dbQuery {
            TariffChangeTable
                .selectAll()
                .where { TariffChangeTable.changeId eq changeId }
                .firstOrNull()
                ?.toRecord()
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRecord() =
        TariffChangeRecord(
            changeId = this[TariffChangeTable.changeId],
            subscriberId = this[TariffChangeTable.subscriberId],
            fromTariffId = this[TariffChangeTable.fromTariffId],
            toTariffId = this[TariffChangeTable.toTariffId],
            status = this[TariffChangeTable.status],
            effectiveAt = Instant.fromEpochMilliseconds(this[TariffChangeTable.effectiveAt]),
        )
}
