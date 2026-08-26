package io.konekt.e2e

import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE ONE COMBINATION EVERY OTHER TEST HERE CANNOT PRODUCE: old code, new schema.
//
// Expand-and-contract is a claim about a rolling deploy — during one, the new schema and the previous
// version's code are live at the same time, and the migration must leave that version working. Every
// test in this repository runs the new code against the new schema, which is precisely the pair that
// cannot fail; a migration that dropped a column, renamed one, or added a NOT NULL without a default
// would be green in all of them and take the running fleet down on deploy.
//
// The server this drives is built from a previous commit and started by `scripts/rolling-check.sh`
// against the schema the CURRENT tree migrated. `konekt.stand.server` points at it, so every helper
// in `Stand` drives the old code without knowing it — which is the point: the old version must serve
// the PRODUCT, not merely start.
class RollingDeployTest {
    @Test
    fun `the previous release still signs a subscriber in against the new schema`() =
        runBlocking {
            Stand.client().use { client ->
                // Sign-in touches `subscriber`, `otp_code` and `session`. A migration that changed any
                // of them incompatibly stops the product at its first screen, and this is the cheapest
                // place that shows.
                val session = Stand.signIn(client)
                assertTrue(session.accessToken.isNotBlank(), "the previous release could not issue a session")
                assertTrue(session.subscriberId.isNotBlank())
            }
        }

    @Test
    fun `the previous release still completes a purchase against the new schema`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.topUp(client, session, majorUnits = 50)

                // THE WHOLE SAGA on old code: the ledger, the entitlement, the petich table and the
                // outbox. `V10__roaming_package.sql` is in the schema this runs against and the code
                // has never heard of it — which is exactly what an expand migration must survive.
                val started =
                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            setBody(CreatePurchaseRequest(PLAN))
                        }.body<PurchaseOrderResponse>()

                val confirmed =
                    client
                        .post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
                            bearerAuth(session.accessToken)
                        }.body<PurchaseOrderResponse>()

                assertEquals(
                    "completed",
                    confirmed.status,
                    "the previous release could not complete a purchase against the new schema: ${confirmed.status}",
                )
            }
        }

    private companion object {
        // A PLAN THE PREVIOUS RELEASE KNOWS. The catalogue is code, so a plan added since that commit
        // is not in the old server's list — and a 422 for an unknown plan would look exactly like a
        // schema incompatibility while being nothing of the kind.
        const val PLAN = "tr-10gb-30d"
    }
}
