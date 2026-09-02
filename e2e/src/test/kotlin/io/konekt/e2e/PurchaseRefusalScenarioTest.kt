package io.konekt.e2e

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.konektWalk
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// BEING REFUSED, AND BEING TOLD WHY — over the whole chain, on the tree a subscriber's client draws.
//
// The reason travels a long way for a short sentence: an interceptor composes a code, writes it into
// a zero-sum ledger row, the use case reads it back onto the order view, and the screen turns it into
// copy and into a control. Every one of those seams has its own test, and for a long time the chain
// still delivered nothing: the code was never written, so the screen printed one constant for all
// five refusals and offered a **Back** button to somebody who was short of money (`B-68`).
//
// A test per seam cannot notice that. This asks the assembled product the question a person asks.
class PurchaseRefusalScenarioTest {
    private val plan = "tr-10gb-30d"

    @Test
    fun `a purchase beyond the balance says so in money and offers the way to fix it`() =
        runBlocking {
            Stand.client().use { client ->
                // NO TOP-UP. Every other scenario in this suite begins with one, and this is the one
                // that must not: an empty balance is the precondition.
                val session = Stand.signIn(client)

                val order =
                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            setBody(CreatePurchaseRequest(plan))
                        }.body<PurchaseOrderResponse>()

                assertEquals(
                    OrderStatuses.REJECTED,
                    order.status,
                    "a purchase on an empty balance was not refused",
                )

                val screen = client.orderScreen(session, order.orderId)

                val banner =
                    screen
                        .konektWalk()
                        .filterIsInstance<TextComponent>()
                        .filter { it.id == "purchase-rejected" }
                        .singleOrNull()
                assertNotNull(banner, "the refusal screen states nothing")

                // BOTH NUMBERS. "You do not have enough" is a sentence somebody has to do arithmetic
                // on before they know what to type into the top-up field. The digits rather than the
                // whole string: the symbol and the separator belong to the formatter and its own
                // tests own them.
                assertTrue("12" in banner.text, "the price is not on the refusal: ${banner.text}")
                assertTrue("0" in banner.text, "the balance is not on the refusal: ${banner.text}")
                // And it does not contradict itself: nothing was held, so nothing was charged.
                assertTrue(
                    "nothing was charged" in banner.text.lowercase(),
                    "the refusal does not say the money is untouched: ${banner.text}",
                )

                val buttons = screen.konektWalk().filterIsInstance<ButtonComponent>()
                val topUp = buttons.singleOrNull { it.id == "purchase-rejected-top-up" }
                assertNotNull(
                    topUp,
                    "refused for want of money and given no way to add any: ${buttons.map { it.text }}",
                )
                assertEquals(NavigateAction(TOP_UP_DEEPLINK), topUp.action)
            }
        }

    // THE CONTROL FOR THE CONTROL, and it is not decoration. The assertion above passes on a build
    // that draws a top-up on EVERY refusal — which would be a button that changes nothing for four of
    // the five, and is the shape the old generic sentence had.
    @Test
    fun `a plan that is not on sale is refused differently, and sends them to the catalogue`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                // Plenty of money, so the only thing wrong is the plan.
                Stand.topUp(client, session, majorUnits = 50)

                val order =
                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            setBody(CreatePurchaseRequest(SOLD_OUT_PLAN))
                        }.body<PurchaseOrderResponse>()

                assertEquals(OrderStatuses.REJECTED, order.status)

                val screen = client.orderScreen(session, order.orderId)
                val buttons = screen.konektWalk().filterIsInstance<ButtonComponent>()

                assertTrue(
                    buttons.none { it.id == "purchase-rejected-top-up" },
                    "offered a top-up to somebody whose plan left the catalogue",
                )
                assertNotNull(
                    buttons.singleOrNull { it.id == "purchase-rejected-plans" },
                    "no way back to the catalogue: ${buttons.map { it.text }}",
                )

                // The two refusals do not read the same, which is the whole of this item.
                val text =
                    screen
                        .konektWalk()
                        .filterIsInstance<TextComponent>()
                        .filter { it.id == "purchase-rejected" }
                        .single()
                        .text
                assertTrue(
                    "balance" !in text.lowercase(),
                    "a refusal about the catalogue blamed the subscriber's balance: $text",
                )
            }
        }

    private suspend fun HttpClient.orderScreen(
        session: Stand.Session,
        orderId: String,
    ): KompotComponent =
        Stand.json.decodeKompotComponent(
            get(OrderScreen(orderId = orderId)) { bearerAuth(session.accessToken) }.bodyAsText(),
        )

    private companion object {
        // The catalogue's one plan that is off sale, which is what `PlansScreen` draws as "Sold out".
        const val SOLD_OUT_PLAN = "us-20gb-30d"
    }
}
