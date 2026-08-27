package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.standard.LoadPageAction
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.ButtonEmphasis
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.feature.purchase.server.domain.HistoryEntry
import io.konekt.feature.purchase.server.domain.HistoryFilter
import io.konekt.feature.purchase.server.domain.HistoryKind
import io.konekt.feature.purchase.server.domain.HistoryPage
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.shared.api.HistoryFilters
import io.konekt.feature.shell.shared.api.ORDERS_DEEPLINK
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
    // THE FILTER GOES IN THE URL WITH THE CURSOR, and leaving it out is the bug this shape prevents:
    // a keyset cursor is a position in a FILTERED list, so the next page has to be asked for from the
    // same list the boundary came from. Without it, "load more" under `Refunded` appends rows the
    // subscriber filtered out.
    fun pageUrl(
        cursor: String?,
        filter: HistoryFilter,
    ): String {
        val query =
            listOfNotNull(
                cursor?.let { "cursor=$it" },
                // The default is omitted rather than spelled: a URL that says `filter=all` and one
                // that says nothing must not be two addresses for one list.
                filter.takeIf { it != HistoryFilter.ALL }?.let { "filter=${it.wireName()}" },
            )
        return "/api/v1/screens/history/page" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }

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

    // THE CHIPS SIT ABOVE THE LIST, so the column exists whether or not there is a shell — which is
    // a change from the shape every recording was taken against, where a screen with no chrome WAS
    // the paginated list. A root that is sometimes a list and sometimes a column was already awkward;
    // now it is always a column, and the bar is one more child when there is one.
    fun build(
        page: HistoryPage,
        titles: PlanTitles,
        filter: HistoryFilter = HistoryFilter.ALL,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "orders",
            spacing = 12,
            children = listOfNotNull(chips(filter), list(page, titles, filter), nav),
        )

    // WHICH SLICE IS OPEN, said by the SERVER — the same argument the bottom bar's `selected` makes.
    // A client deciding it by reading its own address would be a second opinion about which filter is
    // on, and the two disagree the first time an address gains a parameter.
    //
    // Buttons rather than a chip type: `ButtonEmphasis` already distinguishes the chosen one from the
    // rest, and a `chip` on the wire would be a twelfth name for a control this vocabulary can
    // already express.
    private fun chips(filter: HistoryFilter): KompotComponent =
        RowComponent(
            id = "history-filters",
            spacing = 8,
            children =
                HistoryFilter.entries.map { slice ->
                    ButtonComponent(
                        id = "history-filter-${slice.wireName()}",
                        text = slice.label(),
                        // A deeplink with the filter on it, so pressing a chip is an ordinary
                        // `navigate` and the client needs to know nothing about filtering.
                        action =
                            NavigateAction(
                                ORDERS_DEEPLINK +
                                    if (slice == HistoryFilter.ALL) "" else "?filter=${slice.wireName()}",
                            ),
                        variant = if (slice == filter) ButtonEmphasis.PRIMARY else ButtonEmphasis.QUIET,
                    )
                },
        )

    private fun list(
        page: HistoryPage,
        titles: PlanTitles,
        filter: HistoryFilter,
    ): KompotComponent =
        PaginatedListComponent(
            id = "history",
            initialItems = page.entries.map { row(it, titles) },
            loadMoreAction = page.next?.let { LoadPageAction(pageUrl(it.encode(), filter)) },
            // Drawn rather than left blank. An empty list and a list that failed to load look
            // identical as nothing, and only one of them is worth waiting for.
            //
            // AND IT SAYS WHICH EMPTY IT IS. "Nothing here yet" under `Refunded` is wrong twice: this
            // subscriber may have plenty of history, and the sentence sends them looking for a fault
            // instead of pressing `All`.
            emptyState =
                TextComponent(
                    id = "history-empty",
                    text =
                        when (filter) {
                            HistoryFilter.ALL -> {
                                "Nothing here yet. Your purchases and top-ups will appear on this screen."
                            }

                            HistoryFilter.ACTIVE -> {
                                "No plan is running on this line right now."
                            }

                            HistoryFilter.REFUNDED -> {
                                "Nothing has been refunded."
                            }
                        },
                ),
        )

    fun page(
        page: HistoryPage,
        titles: PlanTitles,
        filter: HistoryFilter = HistoryFilter.ALL,
    ): KompotPageResponse =
        KompotPageResponse(
            items = page.entries.map { row(it, titles) },
            // null is what stops the client asking, and it is derived from having fetched one row
            // more than asked for rather than from a count.
            nextLoadAction = page.next?.let { LoadPageAction(pageUrl(it.encode(), filter)) },
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

// The word on the wire and the word on the button, kept beside each other so a slice cannot gain one
// and not the other. `HistoryFilters` is the shared spelling; this is the mapping to it.
private fun HistoryFilter.wireName(): String =
    when (this) {
        HistoryFilter.ALL -> HistoryFilters.ALL
        HistoryFilter.ACTIVE -> HistoryFilters.ACTIVE
        HistoryFilter.REFUNDED -> HistoryFilters.REFUNDED
    }

private fun HistoryFilter.label(): String =
    when (this) {
        HistoryFilter.ALL -> "All"

        HistoryFilter.ACTIVE -> "Active"

        // "Refunded" and not "Reversed", which is the word the ROW uses. The row says what happened
        // to one order; the chip names what somebody is looking for, and they look for a refund.
        HistoryFilter.REFUNDED -> "Refunded"
    }
