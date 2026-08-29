package io.konekt.e2e

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.konekt.components.CounterStates
import io.konekt.components.OrderStatuses
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.usage.shared.api.HomeScreenResource
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

// B-19'S TWO ACCEPTANCE CRITERIA over the whole stand, which is the only level at which either of
// them is actually a claim about the product.
//
// The repository test below this one proves the columns; this proves that buying a Turkey package
// through the real saga leaves the real home screen saying "not counting yet", and that an arrival
// travelling the real broker turns it into a countdown. Between those two facts sit five processes,
// and every one of them can drop this on the floor.
class RoamingScenarioTest {
    private val roamingPlan = "tr-10gb-30d"

    @Test
    fun `a package bought at home is on the screen, full, and not counting`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.topUp(client, session, majorUnits = 50)

                buy(client, session, roamingPlan)

                val card =
                    Stand.awaitOrExplain("the roaming package to appear on the home screen") {
                        client
                            .homeScreen(session.accessToken)
                            .all<UsageCounterCardComponent>()
                            .firstOrNull { it.title == "Turkey data" }
                    }

                // AC1. The word on the wire is what a client renders differently, and it is the only
                // thing on this card that can say "bought, not started" — the bar is full either way.
                assertEquals(CounterStates.DORMANT, card.state)
                assertTrue(
                    card.valueText.endsWith("ready"),
                    "a dormant package says ready, not left: ${card.valueText}",
                )
                assertEquals("10 GB ready", card.valueText)

                val caption = assertNotNull(card.captionText, "a dormant package with no explanation")
                assertTrue("first connect" in caption, caption)
                // And no end date, because it has no start yet. A package bought today that already
                // says when it ends is the defect this whole feature exists to prevent.
                assertTrue("ends" !in caption, "a package that has not started must not have an end date: $caption")
            }
        }

    @Test
    fun `first use abroad starts it, and the screen counts down from that moment`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.topUp(client, session, majorUnits = 50)
                buy(client, session, roamingPlan)

                // WAITED FOR AS DORMANT FIRST, and that is not a formality: the assertion after the
                // arrival is that the state CHANGED, and without seeing the dormant state first a
                // simulator that started every package on the first tick would satisfy it.
                Stand.awaitOrExplain("the roaming package to appear, and to be dormant before anything starts it") {
                    client
                        .homeScreen(session.accessToken)
                        .all<UsageCounterCardComponent>()
                        .firstOrNull { it.title == "Turkey data" && it.state == CounterStates.DORMANT }
                }

                // THE ARRIVAL, AND NOTHING CALLS FOR IT. `B-88` deleted the development route that
                // used to start a package — public, taking `subscriberId` from the query — and moved
                // arrival into the traffic simulator: a package lies dormant for a stated time and
                // then the simulation flies its owner out.
                //
                // So this test does not act here. It WAITS, which is what makes it a test of the
                // product rather than of a route: the same pipe real traffic takes — broker,
                // consumer, package, screen — with nothing outside the process deciding.
                //
                // The stand sets `SIMULATED_ARRIVAL_AFTER_SECONDS` low so this wait is seconds rather
                // than the ninety a demonstration wants.
                val started =
                    Stand.awaitOrExplain("the package to start counting after the arrival") {
                        client
                            .homeScreen(session.accessToken)
                            .all<UsageCounterCardComponent>()
                            .firstOrNull { it.title == "Turkey data" && it.state != CounterStates.DORMANT }
                    }

                // AC2, both halves. It counts down…
                assertTrue(started.valueText.endsWith("left"), "a started package says left: ${started.valueText}")
                val caption = assertNotNull(started.captionText, "a started package with no dates")
                // …and its expiry is dated from the start rather than from the purchase, which on a
                // stand bought moments ago means both dates are present and the copy names them.
                assertTrue("Started" in caption && "ends" in caption, caption)

                // And now the simulator has something to tick: a started package keeps counting down
                // on its own, which a dormant one must never do.
                val spent =
                    Stand.awaitOrExplain("the simulator to keep spending the started package") {
                        client
                            .homeScreen(session.accessToken)
                            .all<UsageCounterCardComponent>()
                            .firstOrNull { it.title == "Turkey data" && it.valueText != started.valueText }
                    }
                assertTrue(spent.valueText.endsWith("left"), spent.valueText)
            }
        }

    private suspend fun buy(
        client: HttpClient,
        session: Stand.Session,
        planId: String,
    ) {
        val started =
            client
                .post(Purchases()) {
                    bearerAuth(session.accessToken)
                    setBody(CreatePurchaseRequest(planId))
                }.body<PurchaseOrderResponse>()

        val confirmed =
            client
                .post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
                    bearerAuth(session.accessToken)
                }.body<PurchaseOrderResponse>()

        assertEquals(OrderStatuses.COMPLETED, confirmed.status, "the purchase did not complete: ${confirmed.status}")
    }

    private suspend fun HttpClient.homeScreen(token: String): KompotComponent =
        Stand.json.decodeKompotComponent(
            get(HomeScreenResource()) { bearerAuth(token) }.bodyAsText(),
        )
}
