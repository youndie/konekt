package io.konekt.e2e

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.RowComponent
import io.konekt.components.CounterStates
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The scenario this build exists to show, over five processes.
//
// Every test below this level can pass while the chain is broken at a seam, because each of them owns
// one end of it: the saga test owns petich, the outbox test owns the relay, the broker test owns
// booblik. This owns the whole, and it is the only thing that does.
//
// `runBlocking` rather than `runTest`: everything here is real I/O across a network, and a virtual
// clock would cancel the first suspension inside somebody else's timeout.
class PurchaseScenarioTest {
    private val plan = "tr-10gb-30d"

    @Test
    fun `a purchase that is confirmed completes, and the allowance lands`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                // Seeded, because nothing in the product adds money yet (B-40).
                Stand.topUp(client, session, majorUnits = 50)

                val started =
                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            setBody(CreatePurchaseRequest(plan))
                        }.let { response ->
                            // 202 and not 201: the usual answer is a saga waiting for a confirmation,
                            // and telling a client the resource is created when the money has only
                            // been held is a lie it would have to unlearn.
                            assertEquals(HttpStatusCode.Accepted, response.status)
                            response.body<PurchaseOrderResponse>()
                        }

                assertEquals(OrderStatuses.AWAITING_CONFIRMATION, started.status)
                assertEquals("CONFIRM", started.requiredAction)

                val confirmed =
                    client
                        .post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
                            bearerAuth(session.accessToken)
                        }.body<PurchaseOrderResponse>()

                assertEquals(OrderStatuses.COMPLETED, confirmed.status)

                // AND THE ALLOWANCE LANDED, which is the half that crosses features: the saga's
                // provisioning step grants what the plan is made of, and the home screen is built by
                // a different feature reading it. A completed order with an empty home screen is the
                // shape of failure this assertion exists for — and it was the real state of this
                // build until B-07.
                val home =
                    Stand.awaitOrExplain("the allowance to appear on the home screen") {
                        val screen = client.homeScreen(session.accessToken)
                        screen.all<UsageCounterCardComponent>().firstOrNull { it.id == "counter-data" }
                    }

                assertTrue(home.valueText.endsWith("left"), "the card does not say what is left: ${home.valueText}")
                assertEquals(CounterStates.NORMAL, home.state)
            }
        }

    @Test
    fun `a purchase the provider refuses is rolled back, and the screen says so in money`() =
        runBlocking {
            // AGAINST THE SECOND SERVER, whose payment mock refuses. The mode is read once at startup,
            // so this is a service rather than a switch — and the only other way to reach the
            // compensated branch is to abandon a confirmation and wait out its five-minute deadline.
            Stand.client(Stand.decliningUrl).use { client ->
                val session = Stand.signIn(client)
                // THE MONEY GOES IN THROUGH THE APPROVING SERVER, and it has to. The payment mock
                // refuses in both directions on this one, so a top-up here would be refused too and
                // the purchase would then fail for want of a balance rather than for the reason this
                // scenario is about. The two servers share one database, so a session obtained here
                // spends what was put in there.
                Stand.client().use { approving -> Stand.topUp(approving, session, majorUnits = 50) }

                val started =
                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            setBody(CreatePurchaseRequest(plan))
                        }.body<PurchaseOrderResponse>()

                val settled =
                    client
                        .post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
                            bearerAuth(session.accessToken)
                        }.body<PurchaseOrderResponse>()

                // COMPENSATED and not FAILED. petich ends a cleanly rolled-back saga in FAILED, and
                // showing a subscriber "failed" would be wrong twice: nothing failed from their side,
                // and the hold was reversed — which is the fact the screen exists to state.
                assertEquals(OrderStatuses.COMPENSATED, settled.status)

                val reversal = assertNotNull(settled.reversalText, "a rollback with nothing said about it")
                assertTrue("returned to your balance" in reversal, reversal)

                // The balance really came back, read through the product rather than out of the
                // database: a rollback that only the ledger knows about is a rollback the subscriber
                // cannot see.
                val home = client.homeScreen(session.accessToken)
                val balance = home.all<io.github.youndie.kompot.standard.TextComponent>().map { it.text }
                assertTrue(balance.any { it == "$50" }, "the balance did not come back: $balance")
            }
        }

    @Test
    fun `the order appears in the history it belongs to`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.topUp(client, session, majorUnits = 50)

                val started =
                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            setBody(CreatePurchaseRequest(plan))
                        }.body<PurchaseOrderResponse>()
                client.post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
                    bearerAuth(session.accessToken)
                }

                val history =
                    Stand.awaitOrExplain("the order to appear in the history") {
                        val screen =
                            Stand.json.decodeKompotComponent(
                                client
                                    .get(
                                        io.konekt.feature.purchase.shared.api
                                            .HistoryScreenResource(),
                                    ) {
                                        bearerAuth(session.accessToken)
                                    }.bodyAsText(),
                            )
                        screen.all<OrderRowComponent>().firstOrNull { it.reference == started.orderId.take(8) }
                    }

                assertEquals(OrderStatuses.COMPLETED, history.status)
            }
        }

    private suspend fun io.ktor.client.HttpClient.homeScreen(token: String): KompotComponent =
        Stand.json.decodeKompotComponent(
            get(HomeScreenResource()) { bearerAuth(token) }.bodyAsText(),
        )
}

// Walks the containers this product actually builds. A history screen is a `paginated_list` and not
// a column, which the first version of this helper did not reach — and the failure read as "the order
// never appeared" rather than "the test looked in the wrong place".
internal fun KompotComponent.walk(): List<KompotComponent> =
    listOf(this) +
        when (this) {
            is ColumnComponent -> children.flatMap { it.walk() }
            is RowComponent -> children.flatMap { it.walk() }
            is PaginatedListComponent -> initialItems.flatMap { it.walk() }
            else -> emptyList()
        }

internal inline fun <reified T : KompotComponent> KompotComponent.all(): List<T> = walk().filterIsInstance<T>()
