package io.konekt.feature.purchase.shared.api

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

@Resource("/api/v1/purchases")
class Purchases {
    @Resource("{orderId}")
    class ById(
        val parent: Purchases = Purchases(),
        val orderId: String,
    ) {
        // Answering the confirmation the saga is waiting for. A separate request rather than a
        // parameter of the first one, because the wait is the point: the saga holds nothing open
        // while it waits, and the subscriber may take a minute to read a code.
        @Resource("confirm")
        class Confirm(
            val parent: ById,
        )
    }
}

// The screen for one order, as a component tree. A separate resource from the order itself because
// the two answer different questions: one is the order's state as data, the other is what to draw.
@Resource("/api/v1/screens/orders/{orderId}")
class OrderScreen(
    val orderId: String,
)

@Resource("/api/v1/screens/history")
class HistoryScreenResource(
    // WHICH SLICE, and it is a query parameter rather than a path because it is not a different
    // screen: the same list, the same cursor, one predicate narrower. A path per filter would make
    // three addresses that a deeplink, a back stack and a conformance walk each have to know about
    // separately.
    val filter: String = HistoryFilters.ALL,
) {
    // The next page. A resource of its own rather than a query parameter on the screen, because the
    // two answer different things: one is a screen to draw, the other is a page of items to append.
    @Resource("page")
    class Page(
        val parent: HistoryScreenResource = HistoryScreenResource(),
        val cursor: String? = null,
        // THE FILTER TRAVELS WITH THE CURSOR, and it has to: a keyset cursor is a position in a
        // FILTERED list. Asking for the next page without it walks a different list from the
        // boundary of this one, which silently returns rows the subscriber filtered out.
        val filter: String = HistoryFilters.ALL,
    )
}

// The three slices, as words on the wire rather than an enum, for the reason every open string in
// this build is one: a value a client does not know must degrade rather than fail to decode. An
// unrecognised filter is the whole list — the answer that shows too much rather than too little,
// which is the right way round for a list somebody is searching.
object HistoryFilters {
    const val ALL = "all"

    // A purchase that granted something and still holds it. NOT a top-up: money that arrived is
    // finished, not running, and the canvas draws the active row as a plan with data left on it.
    const val ACTIVE = "active"

    // Anything that was undone, either direction — a purchase reversed or a top-up taken back. It is
    // the slice somebody opens when they are looking for money, so it must not exclude the half that
    // went the other way.
    const val REFUNDED = "refunded"

    val ALL_OF_THEM: List<String> = listOf(ALL, ACTIVE, REFUNDED)
}

@Serializable
data class CreatePurchaseRequest(
    val planId: String,
)

// The order as a subscriber sees it. `status` is the saga's phase in the product's own words — see
// OrderStatus for why petich's word for a clean rollback is not the word to show anybody.
@Serializable
data class PurchaseOrderResponse(
    val orderId: String,
    val status: String,
    val planId: String,
    val priceText: String,
    // Set while the saga waits. The client shows the confirmation screen when this is present, which
    // is one field rather than a second endpoint to ask "what now".
    val requiredAction: String? = null,
    // Filled on the compensated branch: what was reversed, stated in money. The canvas draws this
    // sentence, and it is the difference between a rollback a subscriber can reconcile and one they
    // ring support about.
    val reversalText: String? = null,
)

// THE CATALOGUE AS A SCREEN, and it is what `app://plans` has pointed at since B-07 without anything
// being there. It lives with the purchase feature because the subject is the plans; the home screen's
// banner is what sends a subscriber here.
@Resource("/api/v1/screens/plans")
class PlansScreenResource {
    // ONE PLAN, AND THE SCREEN THAT WAS MISSING BETWEEN THE CATALOGUE AND THE PURCHASE.
    //
    // Pressing a card used to CREATE AN ORDER. The canvas draws a detail screen in between — what
    // the plan includes, how it activates, and a "Buy for …" that is the first thing to spend
    // anything — and its absence made the catalogue a page of buttons that charge you.
    //
    // Nested rather than a path of its own so that `app://plans/<id>` resolves with no new entry in
    // the client's route map: the resolver carries everything after the matched prefix across
    // unchanged, so the deeplink for the catalogue already addresses this.
    @Resource("{planId}")
    class ById(
        val parent: PlansScreenResource = PlansScreenResource(),
        val planId: String,
    )
}

// The deeplink the server puts on that banner, spelled once. Three parties use it — the screen that
// sends it, the client that resolves it, and the test that proves the two agree — and a fourth
// spelling is a button that goes nowhere in exactly one of them.
const val PLANS_DEEPLINK: String = "app://plans"
