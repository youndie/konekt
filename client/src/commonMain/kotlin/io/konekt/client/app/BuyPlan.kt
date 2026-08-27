package io.konekt.client.app

import io.github.youndie.kompot.KompotAction
import io.konekt.feature.purchase.shared.api.BuyPlanAction
import io.konekt.feature.purchase.shared.api.ConfirmPurchaseAction
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.serialization.serializer

// BUYING, HANDLED BY THE COMPOSITION ROOT AND NOT BY THE HOLDER.
//
// `KonektApp` knows about screens, addresses and one transition. It does not know what a purchase is,
// and a holder that posted to `/api/v1/purchases` would be a screen holder with an opinion about the
// product — which is the shape that turns a reusable root into this application's root.
//
// So the runner wires this in as its `onAction`. What comes back is the address of the order screen,
// and the holder is handed it the same way a `navigate` hands it one: the verb differs, the movement
// does not.
class BuyPlan(
    private val http: HttpClient,
) {
    // Returns where to go next, or null when the action was not a purchase. Null rather than an
    // exception because the handler is a chain: an action this does not recognise belongs to whatever
    // the runner does with the rest.
    suspend fun addressFor(action: KompotAction): String? =
        when (action) {
            is BuyPlanAction -> orderScreen(create(action.planId))

            // CONFIRMING, and it lands here rather than anywhere else because it is the second half
            // of the same verb: the saga suspends after `buy_plan` and does nothing at all until
            // this arrives. Both answer with the same order and both end on the same screen — what
            // differs is only which state that screen is in.
            is ConfirmPurchaseAction -> orderScreen(confirm(action.orderId))

            else -> null
        }

    // 202 AND NOT 201, and the client must not assume otherwise: the usual answer is a saga waiting
    // for a confirmation. What comes back is an order that exists and is not finished, which is
    // exactly what the order screen is for.
    private suspend fun create(planId: String): PurchaseOrderResponse =
        http
            .post(Purchases()) { setBody(CreatePurchaseRequest(planId)) }
            .body()

    // The order comes back as it now is — completed, or refused by the payment mock and on its way
    // to a rollback. Either way the answer is the same screen, which is what makes the compensated
    // branch something a subscriber SEES rather than something a test asserts.
    private suspend fun confirm(orderId: String): PurchaseOrderResponse =
        http
            .post(Purchases.ById.Confirm(parent = Purchases.ById(orderId = orderId)))
            .body()

    private fun orderScreen(order: PurchaseOrderResponse): String =
        ResourcesFormat()
            .encodeToPathPattern(serializer<OrderScreen>())
            // The pattern carries a placeholder; the id is the one thing this client fills in. Reading
            // the pattern rather than typing the path keeps the ADDRESS spelled once, in the
            // annotation, the same way every other address in this client is.
            .replace("{orderId}", order.orderId)
            .let { if (it.startsWith("/")) it else "/$it" }
}
