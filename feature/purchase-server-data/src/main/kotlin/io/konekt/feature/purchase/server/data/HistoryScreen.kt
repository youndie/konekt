package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.standard.LoadPageAction
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.feature.purchase.server.domain.HistoryEntry
import io.konekt.feature.purchase.server.domain.HistoryPage
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.money.DayFormat
import io.konekt.money.MoneyFormat

// History, as a toolkit list of our own rows.
//
// `paginated_list` rather than a list of our own: pagination, load-more and termination come from
// kompot, which also means the conformance walk checks them. What stays ours is the row — and the row
// is the only part that is about this product.
object HistoryScreen {
    // The path the client asks for the next page. It is the ONE place this string exists on the
    // server; the client never builds it, because the cursor inside it is opaque by design.
    fun pageUrl(cursor: String?): String = "/api/v1/screens/history/page" + (cursor?.let { "?cursor=$it" } ?: "")

    fun build(page: HistoryPage): KompotComponent =
        PaginatedListComponent(
            id = "history",
            initialItems = page.entries.map(::row),
            loadMoreAction = page.next?.let { LoadPageAction(pageUrl(it.encode())) },
            // Drawn rather than left blank. An empty list and a list that failed to load look
            // identical as nothing, and only one of them is worth waiting for.
            emptyState =
                TextComponent(
                    id = "history-empty",
                    text = "Nothing here yet. Your purchases and top-ups will appear on this screen.",
                ),
        )

    fun page(page: HistoryPage): KompotPageResponse =
        KompotPageResponse(
            items = page.entries.map(::row),
            // null is what stops the client asking, and it is derived from having fetched one row
            // more than asked for rather than from a count.
            nextLoadAction = page.next?.let { LoadPageAction(pageUrl(it.encode())) },
        )

    private fun row(entry: HistoryEntry): OrderRowComponent =
        OrderRowComponent(
            id = "history-${entry.orderId}",
            reference = entry.orderId.take(8),
            title = entry.title,
            dateText = DayFormat.dayAndMonth(entry.at),
            // The debit, always, even on a compensated order. The row says what left; the note says
            // it came back. Netting the two to zero would make a reversal invisible, which is the one
            // thing this screen must not do.
            amountText = MoneyFormat.format(-entry.amount, signed = true),
            status =
                when (entry.status) {
                    OrderStatus.COMPLETED -> OrderStatuses.COMPLETED
                    OrderStatus.COMPENSATED -> OrderStatuses.COMPENSATED
                    else -> OrderStatuses.PENDING
                },
            statusText =
                when (entry.status) {
                    OrderStatus.COMPLETED -> "Paid"
                    OrderStatus.COMPENSATED -> "Reversed"
                    else -> "Awaiting confirmation"
                },
            noteText =
                entry.reversal?.let {
                    "${MoneyFormat.format(it.amount)} returned to balance on ${DayFormat.dayAndMonth(it.at)}" +
                        " — nothing was activated."
                },
        )
}
