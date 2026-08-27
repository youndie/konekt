package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.standard.LoadPageAction
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.feature.purchase.server.domain.HistoryEntry
import io.konekt.feature.purchase.server.domain.HistoryKind
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

    // WRAPPED IN A COLUMN WHEN THERE IS CHROME, and left alone when there is not.
    //
    // The root of this screen is a paginated list, and a bar cannot be a child of one — its items
    // are the history. So the shell forces a wrapper, and the wrapper is added only when a shell
    // exists: a deployment without one keeps the root it had, which is the shape every recording and
    // every conformance walk was taken against.
    // THE PLAN'S NAME, LOOKED UP RATHER THAN STORED. The entitlement row keeps a plan id, because an
    // id is what an entitlement is about; the history drew it, so a subscriber read "eu-5gb-14d"
    // where the canvas draws "Europe · 5 GB · 14 days".
    //
    // A FUNCTION rather than the catalogue itself, and it FALLS BACK TO THE ID. A plan withdrawn from
    // sale still has orders in somebody's history, and a row that vanished — or drew a blank — because
    // the catalogue no longer lists it would lose the very order a subscriber is looking for.
    fun interface PlanTitles {
        fun of(planId: String): String
    }

    fun build(
        page: HistoryPage,
        titles: PlanTitles,
        nav: KompotComponent? = null,
    ): KompotComponent =
        nav?.let { ColumnComponent(id = "orders", spacing = 12, children = listOf(list(page, titles), it)) }
            ?: list(page, titles)

    private fun list(
        page: HistoryPage,
        titles: PlanTitles,
    ): KompotComponent =
        PaginatedListComponent(
            id = "history",
            initialItems = page.entries.map { row(it, titles) },
            loadMoreAction = page.next?.let { LoadPageAction(pageUrl(it.encode())) },
            // Drawn rather than left blank. An empty list and a list that failed to load look
            // identical as nothing, and only one of them is worth waiting for.
            emptyState =
                TextComponent(
                    id = "history-empty",
                    text = "Nothing here yet. Your purchases and top-ups will appear on this screen.",
                ),
        )

    fun page(
        page: HistoryPage,
        titles: PlanTitles,
    ): KompotPageResponse =
        KompotPageResponse(
            items = page.entries.map { row(it, titles) },
            // null is what stops the client asking, and it is derived from having fetched one row
            // more than asked for rather than from a count.
            nextLoadAction = page.next?.let { LoadPageAction(pageUrl(it.encode())) },
        )

    private fun row(
        entry: HistoryEntry,
        titles: PlanTitles,
    ): OrderRowComponent =
        OrderRowComponent(
            id = "history-${entry.reference}",
            reference = entry.reference.take(8),
            // A TOP-UP NAMES NOTHING, so the row names the movement. The canvas writes it as
            // "Top-up · 1 000 ₽" — the amount is already the column beside it, so the title is the
            // word alone; a plan title would be a lookup with nothing to find.
            title =
                when (entry.kind) {
                    HistoryKind.PURCHASE -> entry.planId?.let(titles::of) ?: entry.reference.take(8)
                    HistoryKind.TOP_UP -> "Top-up"
                },
            dateText = DayFormat.dayAndMonth(entry.at),
            // SIGNED AS THE LEDGER WROTE IT, and this line used to negate. Negating was right while
            // every row was a debit and became wrong the moment credits joined the list: a top-up
            // would have been drawn as money leaving. What is NOT netted is a reversal — the row says
            // what moved and the note says it came back, because netting them to zero makes a
            // reversal invisible, which is the one thing this screen must not do.
            amountText = MoneyFormat.format(entry.amount, signed = true),
            // NO `else`, in both `when`s below. `OrderStatus` is an enum, so an exhaustive when makes
            // a value added there and not thought about here a compile error — which is how this
            // repository prefers to be told. The `else` that used to be here drew a REJECTED order as
            // pending and told the subscriber it was "Awaiting confirmation": a rule had refused the
            // order and the screen asked them to wait for something that was never coming.
            status =
                when (entry.status) {
                    OrderStatus.COMPLETED -> OrderStatuses.COMPLETED
                    OrderStatus.COMPENSATED -> OrderStatuses.COMPENSATED
                    OrderStatus.REJECTED -> OrderStatuses.REJECTED
                    OrderStatus.COMPENSATING -> OrderStatuses.COMPENSATING
                    OrderStatus.PENDING -> OrderStatuses.PENDING
                    OrderStatus.AWAITING_CONFIRMATION -> OrderStatuses.AWAITING_CONFIRMATION
                },
            statusText =
                when (entry.status) {
                    // "Paid" is a word about money LEAVING, and a credit borrowing it reads as a
                    // charge on the one screen a subscriber reconciles against their bank. The two
                    // terminal states are the only ones that differ: everything below is about a
                    // saga's progress, which is the same question whichever way the money was going.
                    OrderStatus.COMPLETED -> {
                        when (entry.kind) {
                            HistoryKind.PURCHASE -> "Paid"
                            HistoryKind.TOP_UP -> "Added"
                        }
                    }

                    OrderStatus.COMPENSATED -> {
                        when (entry.kind) {
                            HistoryKind.PURCHASE -> "Reversed"
                            HistoryKind.TOP_UP -> "Taken back"
                        }
                    }

                    // What a subscriber can act on, rather than what petich calls it. "Refused" says
                    // the operation is over; "Awaiting confirmation" said the opposite.
                    OrderStatus.REJECTED -> {
                        "Refused"
                    }

                    // Deliberately not "failed". This is the state a person has to look at, and the
                    // honest thing to tell a subscriber is that somebody is.
                    OrderStatus.COMPENSATING -> {
                        "Being reversed"
                    }

                    OrderStatus.PENDING -> {
                        "In progress"
                    }

                    OrderStatus.AWAITING_CONFIRMATION -> {
                        "Awaiting confirmation"
                    }
                },
            noteText =
                entry.reversal?.let {
                    val what = MoneyFormat.format(it.amount)
                    val day = DayFormat.dayAndMonth(it.at)
                    when (entry.kind) {
                        HistoryKind.PURCHASE -> "$what returned to balance on $day — nothing was activated."

                        // A top-up reversed is the opposite direction and must not borrow the
                        // purchase's sentence: nothing was "returned to balance" — it was taken back
                        // out of it, and a subscriber reading the wrong one goes looking for money
                        // that is not there.
                        HistoryKind.TOP_UP -> "$what was taken back on $day — the payment did not settle."
                    }
                },
        )
}
