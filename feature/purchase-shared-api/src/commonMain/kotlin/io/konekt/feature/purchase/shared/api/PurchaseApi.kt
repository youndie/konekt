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
class HistoryScreenResource {
    // The next page. A resource of its own rather than a query parameter on the screen, because the
    // two answer different things: one is a screen to draw, the other is a page of items to append.
    @Resource("page")
    class Page(
        val parent: HistoryScreenResource = HistoryScreenResource(),
        val cursor: String? = null,
    )
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
