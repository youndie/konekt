package io.konekt.feature.purchase.server.data

import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.HistoryCursor
import io.konekt.feature.purchase.server.domain.HistoryEntry
import io.konekt.feature.purchase.server.domain.HistoryPage
import io.konekt.feature.purchase.server.domain.HistoryRepository
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.Reversal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
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

    override suspend fun page(
        subscriberId: String,
        after: HistoryCursor?,
        limit: Int,
    ): HistoryPage =
        dbQuery {
            // Left join to the RELEASE entry, so a reversal costs no second query per row. The
            // alternative reads once per line, which is invisible on a screen of twenty and is the
            // reason a history screen is slow on the account that has used the product most.
            val joined =
                EntitlementTable.join(
                    LedgerEntryTable,
                    JoinType.LEFT,
                    onColumn = EntitlementTable.orderId,
                    otherColumn = LedgerEntryTable.orderId,
                    // `leftJoin(table) { … }` does not exist in Exposed 1.4 — the constraint goes
                    // through the general `join`, and putting the kind here rather than in the WHERE
                    // is what keeps it a LEFT join: in the WHERE it would silently become an inner
                    // one and every order without a reversal would vanish from the history.
                    additionalConstraint = { LedgerEntryTable.kind eq LedgerEntryTable.RELEASE },
                )

            // ONE ROW MORE THAN ASKED FOR. That extra row is the whole answer to "is there another
            // page" — a COUNT is a second query against a table that is still being written to, and
            // the two can disagree in the moment that matters.
            val rows =
                joined
                    .selectAll()
                    .where {
                        var predicate = EntitlementTable.subscriberId eq subscriberId
                        if (after != null) {
                            // The keyset, and it has to be the PAIR. `created_at < x` alone drops
                            // every row sharing the boundary instant; `<=` alone repeats them
                            // forever. This is strictly-older, or same instant and a smaller id.
                            val boundary = after.before.toEpochMilliseconds()
                            predicate =
                                predicate and
                                (
                                    (EntitlementTable.createdAt less boundary) or
                                        (
                                            (EntitlementTable.createdAt eq boundary) and
                                                (EntitlementTable.orderId less after.orderId)
                                        )
                                )
                        }
                        predicate
                    }.orderBy(
                        EntitlementTable.createdAt to SortOrder.DESC,
                        // The tie-break is part of the ORDER as well as of the cursor. Without it two
                        // rows sharing an instant may come back in a different order on two requests,
                        // and a page boundary between them loops.
                        EntitlementTable.orderId to SortOrder.DESC,
                    ).limit(limit + 1)
                    .toList()

            val page = rows.take(limit)
            val entries =
                page.map { row ->
                    val currency = Currency.valueOf(row[EntitlementTable.currency].trim())
                    HistoryEntry(
                        orderId = row[EntitlementTable.orderId],
                        title = row[EntitlementTable.planId],
                        amount = Money(row[EntitlementTable.priceMinor], currency),
                        at = Instant.fromEpochMilliseconds(row[EntitlementTable.createdAt]),
                        status =
                            when (row[EntitlementTable.status]) {
                                Entitlement.ACTIVE -> OrderStatus.COMPLETED
                                Entitlement.CANCELLED -> OrderStatus.COMPENSATED
                                else -> OrderStatus.AWAITING_CONFIRMATION
                            },
                        reversal =
                            row.getOrNull(LedgerEntryTable.amountMinor)?.let { amount ->
                                Reversal(
                                    amount = Money(amount, currency),
                                    at = Instant.fromEpochMilliseconds(row[LedgerEntryTable.createdAt]),
                                )
                            },
                    )
                }

            HistoryPage(
                entries = entries,
                next =
                    if (rows.size > limit) {
                        entries.lastOrNull()?.let { HistoryCursor(it.at, it.orderId) }
                    } else {
                        null
                    },
            )
        }
}
