package io.konekt.e2e

import io.konekt.components.OrderStatuses
import io.konekt.feature.purchase.shared.api.TopUpResponse
import io.konekt.feature.purchase.shared.api.TopUps
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.request.bearerAuth
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// PUTTING MONEY IN, over the whole chain. Until B-40 this was the hole in the product: an account was
// created with zero, nothing raised it, and every scenario in this suite began with an UPDATE at the
// stand's own database because there was nothing to call.
//
// `runBlocking` rather than `runTest`: everything here is real I/O across a network, and a virtual
// clock would cancel the first suspension inside somebody else's timeout.
class TopUpScenarioTest {
    @Test
    fun `a top-up the provider takes raises the balance, and the money is spendable`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                val topUp = Stand.topUp(client, session, majorUnits = 50)

                assertEquals(OrderStatuses.COMPLETED, topUp.status)
                assertNull(topUp.declineReason, "an approved top-up carries no reason")
                // Formatted by the server, because the client renders text and cannot format money
                // inconsistently. Asserting the DIGITS rather than the whole string: the separator and
                // the symbol are the formatter's business and B-31's tests own them.
                assertTrue("50" in topUp.balanceText, "the balance does not read as 50: ${topUp.balanceText}")

                // Read back through its own address, which is the endpoint a client polls.
                val readBack =
                    client
                        .get(TopUps.ById(topUpId = topUp.topUpId)) { bearerAuth(session.accessToken) }
                        .body<TopUpResponse>()

                assertEquals(topUp.topUpId, readBack.topUpId)
                assertEquals(OrderStatuses.COMPLETED, readBack.status)
            }
        }

    @Test
    fun `a top-up the provider refuses leaves the balance exactly where it was, and says why`() =
        runBlocking {
            // The subscriber is created on the approving server and tops up on the refusing one, so
            // that the balance being asserted is a real one that did not move rather than a zero that
            // never had anywhere to go. The two share one database.
            Stand.client().use { approving ->
                val session = Stand.signIn(approving)
                val before = Stand.topUp(approving, session, majorUnits = 50)

                Stand.client(Stand.decliningUrl).use { declining ->
                    val refused = Stand.topUp(declining, session, majorUnits = 30)

                    // COMPENSATED and not FAILED, the same word the rollback screen uses: petich ends
                    // a cleanly rolled-back saga in FAILED, and nothing failed from the subscriber's
                    // side.
                    assertEquals(OrderStatuses.COMPENSATED, refused.status)
                    // The reason is what makes a refusal something to act on. "The operation did not
                    // go through" is what a subscriber rings support about.
                    assertNotNull(refused.declineReason, "a refused top-up must say why")

                    // THE ASSERTION THIS SCENARIO EXISTS FOR. The balance is the one from before,
                    // to the character — not merely "not 80".
                    assertEquals(
                        before.balanceText,
                        refused.balanceText,
                        "a refused top-up moved the balance",
                    )
                }
            }
        }

    @Test
    fun `an amount below the smallest top-up is refused before any money moves`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                val seeded = Stand.topUp(client, session, majorUnits = 50)

                // One minor unit: above zero, below the floor. Zero and negatives are refused by the
                // same rule, and this is the interesting end of it — a plausible amount that the
                // limits still refuse.
                val refused = Stand.topUpRaw(client, session, amountMinor = 1)

                // REJECTED and not COMPENSATED, and the difference is the point of putting the limits
                // in a VALIDATION step: a Reject refuses before anything happened, so there is nothing
                // to reverse and no compensation runs at all.
                //
                // A string literal because `OrderStatuses` has no word for it — the component
                // vocabulary declares five statuses, the server emits six, and they are not the same
                // five. That is B-41 rather than something to paper over here.
                assertEquals("rejected", refused.status)
                assertNull(refused.declineReason, "nothing declined it — a rule refused it")
                assertEquals(seeded.balanceText, refused.balanceText, "a refused amount moved the balance")
            }
        }
}
