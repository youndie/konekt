package io.konekt.feature.purchase.server.data

import io.konekt.db.tables.AccountTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.HistoryCursor
import io.konekt.feature.purchase.server.domain.HistoryEntry
import io.konekt.feature.purchase.server.domain.HistoryKind
import io.konekt.feature.purchase.server.domain.HistoryPage
import io.konekt.feature.purchase.server.domain.HistoryRepository
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.Reversal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Instant

class ExposedHistoryRepository(
    private val db: Database,
) : HistoryRepository {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    // DRIVEN BY THE LEDGER, and it used to be driven by the entitlement.
    //
    // That was invisible until `B-40`'s screen shipped: a top-up creates no entitlement — it raises a
    // balance — so no top-up could ever be a row. `Top up` and `History` then sat side by side on the
    // balance card and the second did not show what the first did. Section 05 of the canvas draws a
    // top-up in this very list.
    //
    // ONE DRIVING ROW PER MOVEMENT, which is what makes a keyset over one table honest here. A
    // purchase writes a `hold` and a top-up writes a `top_up`, each exactly once —
    // `HoldFundsInterceptor` writes the hold and the pending entitlement together, so a hold without
    // an entitlement cannot exist and the purchase rows are the same set as before. `capture` and
    // `release` are consequences of a movement rather than movements, so they are joined to rather
    // than selected; `decline` is zero-sum and carries a sentence, and nothing was moved to
    // reconcile.
    //
    // The alternative — two queries unioned — gets the cursor wrong by construction: two tables
    // interleave, and a page boundary that falls between them either repeats a row or drops one.
    override suspend fun page(
        subscriberId: String,
        after: HistoryCursor?,
        limit: Int,
    ): HistoryPage =
        dbQuery {
            // The account, because the ledger is keyed by it. A subscriber with no account has no
            // movements, which is a page rather than a failure: it is what a brand-new sign-in looks
            // like.
            val accountId =
                AccountTable
                    .selectAll()
                    .where { AccountTable.subscriberId eq subscriberId }
                    .singleOrNull()
                    ?.get(AccountTable.id)
                    ?: return@dbQuery HistoryPage(entries = emptyList(), next = null)

            // Two LEFT joins and no second query per row: the entitlement carries what a purchase was
            // FOR, and a second ledger row carries what came back. Reading either per line is
            // invisible on a screen of twenty and is why a history screen is slow on the account that
            // has used the product most.
            //
            // `reversals` is an ALIAS of the same table the query is driven by. Without one, Exposed
            // joins `ledger_entry` to itself under one name and the `kind` predicate applies to both
            // — which selects nothing at all, since no row is a hold and a release at once.
            val reversals = LedgerEntryTable.alias("reversal_entry")

            val joined =
                LedgerEntryTable
                    .join(
                        EntitlementTable,
                        JoinType.LEFT,
                        onColumn = LedgerEntryTable.orderId,
                        otherColumn = EntitlementTable.orderId,
                    ).join(
                        reversals,
                        JoinType.LEFT,
                        onColumn = LedgerEntryTable.orderId,
                        otherColumn = reversals[LedgerEntryTable.orderId],
                        // Both reversals, because a purchase and a top-up are undone by different
                        // kinds and a row is one or the other. Putting the constraint here rather
                        // than in the WHERE is what keeps this a LEFT join: in the WHERE it silently
                        // becomes an inner one and every movement that was never reversed vanishes.
                        additionalConstraint = {
                            (reversals[LedgerEntryTable.kind] eq LedgerEntryTable.RELEASE) or
                                (reversals[LedgerEntryTable.kind] eq LedgerEntryTable.TOP_UP_REVERSAL)
                        },
                    )

            // ONE ROW MORE THAN ASKED FOR. That extra row is the whole answer to "is there another
            // page" — a COUNT is a second query against a table that is still being written to, and
            // the two can disagree in the moment that matters.
            val rows =
                joined
                    .selectAll()
                    .where {
                        var predicate =
                            (LedgerEntryTable.accountId eq accountId) and
                                (
                                    (LedgerEntryTable.kind eq LedgerEntryTable.HOLD) or
                                        (LedgerEntryTable.kind eq LedgerEntryTable.TOP_UP)
                                )
                        if (after != null) {
                            // The keyset, and it has to be the PAIR. `created_at < x` alone drops
                            // every row sharing the boundary instant; `<=` alone repeats them
                            // forever. This is strictly-older, or same instant and a smaller id.
                            val boundary = after.before.toEpochMilliseconds()
                            predicate =
                                predicate and
                                (
                                    (LedgerEntryTable.createdAt less boundary) or
                                        (
                                            (LedgerEntryTable.createdAt eq boundary) and
                                                (LedgerEntryTable.id less after.id)
                                        )
                                )
                        }
                        predicate
                    }.orderBy(
                        LedgerEntryTable.createdAt to SortOrder.DESC,
                        // The tie-break is part of the ORDER as well as of the cursor. Without it two
                        // rows sharing an instant may come back in a different order on two requests,
                        // and a page boundary between them loops.
                        LedgerEntryTable.id to SortOrder.DESC,
                    ).limit(limit + 1)
                    .toList()

            val page = rows.take(limit)
            val entries =
                page.map { row ->
                    val currency = Currency.valueOf(row[LedgerEntryTable.currency].trim())
                    val topUp = row[LedgerEntryTable.kind] == LedgerEntryTable.TOP_UP
                    val reversed = row.getOrNull(reversals[LedgerEntryTable.amountMinor])

                    HistoryEntry(
                        reference = row[LedgerEntryTable.orderId].orEmpty(),
                        kind = if (topUp) HistoryKind.TOP_UP else HistoryKind.PURCHASE,
                        planId = row.getOrNull(EntitlementTable.planId),
                        // Signed as the ledger wrote it: a hold is negative, a top-up positive.
                        amount = Money(row[LedgerEntryTable.amountMinor], currency),
                        at = Instant.fromEpochMilliseconds(row[LedgerEntryTable.createdAt]),
                        status =
                            if (topUp) {
                                // A TOP-UP HAS NO ENTITLEMENT TO ASK. It exists because money
                                // arrived, so it is done — unless something after the credit failed
                                // and the saga took it back, which is the only other state it has.
                                if (reversed != null) OrderStatus.COMPENSATED else OrderStatus.COMPLETED
                            } else {
                                when (row.getOrNull(EntitlementTable.status)) {
                                    Entitlement.ACTIVE -> OrderStatus.COMPLETED
                                    Entitlement.CANCELLED -> OrderStatus.COMPENSATED
                                    else -> OrderStatus.AWAITING_CONFIRMATION
                                }
                            },
                        reversal =
                            reversed?.let { amount ->
                                Reversal(
                                    // The magnitude: a reversal of a purchase is written positive and
                                    // one of a top-up negative, and the sentence beside the row reads
                                    // as an amount either way.
                                    amount = Money(kotlin.math.abs(amount), currency),
                                    at =
                                        Instant.fromEpochMilliseconds(
                                            row[reversals[LedgerEntryTable.createdAt]],
                                        ),
                                )
                            },
                    )
                }

            HistoryPage(
                entries = entries,
                next =
                    if (rows.size > limit) {
                        page.lastOrNull()?.let {
                            // FROM THE ROW AND NOT FROM THE ENTRY: the cursor keys on the ledger row's
                            // own id, which the mapped entry deliberately does not carry — a
                            // subscriber's reference is the saga's id, and the two are different
                            // facts that happen to sit on the same line.
                            HistoryCursor(
                                Instant.fromEpochMilliseconds(it[LedgerEntryTable.createdAt]),
                                it[LedgerEntryTable.id],
                            )
                        }
                    } else {
                        null
                    },
            )
        }
}
