package io.konekt.feature.purchase.server.domain

import io.konekt.domain.Money
import io.konekt.domain.suspendRunCatching
import kotlin.time.Instant

// WHICH MOVEMENT A LINE IS, and the two are not the same row wearing a different sign.
//
// A purchase names a plan, has a status that can still change, and can be reversed. A top-up names
// nothing, is over by the time it exists, and its only interesting state is whether it was taken
// back. The screen draws them differently for that reason, and a boolean called `isCredit` would
// hide it.
enum class HistoryKind {
    PURCHASE,
    TOP_UP,
}

// One line of history.
//
// WHAT IS IN THE LIST, and it is a decision rather than a query: everything that MOVED MONEY. A
// purchase refused in validation never held anything, so it is not here — there is nothing to
// reconcile against a bank statement, and a list of refusals is a different screen answering a
// different question. The same rule puts a top-up in: `credit` runs after the provider has settled,
// so a top-up row exists exactly when money arrived.
//
// A compensated order IS here, with its reversal line. A history that quietly omitted what was undone
// would be a history a subscriber cannot reconcile, which is the one job it has.
data class HistoryEntry(
    // The id a subscriber can quote — an order's or a top-up's. Both are saga ids and both are what
    // the result screen shows, which is what makes the row and the screen quotable against each
    // other.
    val reference: String,
    val kind: HistoryKind,
    // The plan, for a purchase. `null` for a top-up, which names nothing — and nullable rather than
    // an empty string so that a screen cannot accidentally look one up.
    val planId: String?,
    // SIGNED, straight from the ledger, and it used to be a magnitude the screen negated. That was
    // fine while every line was a debit; with credits in the list a screen negating everything would
    // draw a top-up as money leaving. Direction is a fact about the movement, so it travels with it.
    val amount: Money,
    val at: Instant,
    val status: OrderStatus,
    // Set when the money came back. The sentence the canvas draws.
    val reversal: Reversal?,
)

// A keyset cursor, not an offset.
//
// An offset skips or repeats when a row lands above it between two pages, and history grows at
// exactly the end a subscriber is reading from. The pair is (instant, ledger row id): the instant
// orders the list and the id breaks ties, because two movements in one millisecond are not
// impossible and a tie-break that is not total is a page boundary that loops.
//
// THE LEDGER ROW'S ID AND NOT THE ORDER'S, since the list became one query over the ledger. They
// coincide for a purchase and a top-up alike today — one driving row each — and the ledger's own key
// is the one that stays total if that ever stops being true.
data class HistoryCursor(
    val before: Instant,
    val id: String,
) {
    // Opaque on the wire — it is this server's business how a page is addressed, and a client that
    // could construct one would be a client depending on the shape of a query.
    fun encode(): String = "${before.toEpochMilliseconds()}:$id"

    companion object {
        fun decode(raw: String?): HistoryCursor? {
            val at = raw?.substringBefore(':')?.toLongOrNull() ?: return null
            val id = raw.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
            return HistoryCursor(Instant.fromEpochMilliseconds(at), id)
        }
    }
}

data class HistoryPage(
    val entries: List<HistoryEntry>,
    // null means the end, and the client's list stops asking. It is derived from having fetched one
    // row more than asked for rather than from counting the table: a count is a second query that can
    // disagree with the first.
    val next: HistoryCursor?,
)

interface HistoryRepository {
    suspend fun page(
        subscriberId: String,
        after: HistoryCursor?,
        limit: Int,
    ): HistoryPage
}

class LoadHistoryUseCase(
    private val history: HistoryRepository,
) {
    suspend operator fun invoke(params: Params): Result<HistoryPage> =
        suspendRunCatching {
            history.page(params.subscriberId, HistoryCursor.decode(params.cursor), params.limit)
        }

    data class Params(
        val subscriberId: String,
        val cursor: String?,
        // Bounded here rather than trusted from the request: a client asking for a million rows is
        // either broken or not a client.
        val limit: Int = DEFAULT_PAGE_SIZE,
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }
}
