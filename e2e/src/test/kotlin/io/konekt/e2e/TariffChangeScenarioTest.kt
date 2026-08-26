package io.konekt.e2e

import io.konekt.components.OrderStatuses
import io.konekt.feature.tariff.shared.api.ChangeTariffRequest
import io.konekt.feature.tariff.shared.api.TariffChangeResponse
import io.konekt.feature.tariff.shared.api.TariffChanges
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// THE SECOND SAGA, over the whole chain — and it exists here rather than only as a unit test for the
// same reason the first one does: every layer below can pass while the chain is broken at a seam,
// because each owns one end of it.
class TariffChangeScenarioTest {
    @Test
    fun `a confirmed change is agreed now and takes effect on a boundary`(): Unit =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                val asked =
                    client
                        .post(TariffChanges()) {
                            bearerAuth(session.accessToken)
                            setBody(ChangeTariffRequest("tr-max"))
                        }.let {
                            // 202 and not 201, like the purchase: the usual answer is a saga waiting
                            // for a confirmation, and calling it created would be a lie the client
                            // has to unlearn.
                            assertEquals(HttpStatusCode.Accepted, it.status)
                            it.body<TariffChangeResponse>()
                        }

                assertEquals(OrderStatuses.AWAITING_CONFIRMATION, asked.status)
                assertEquals("CONFIRM_TARIFF", asked.requiredAction)

                val settled =
                    client
                        .post(TariffChanges.ById.Confirm(TariffChanges.ById(changeId = asked.changeId))) {
                            bearerAuth(session.accessToken)
                        }.body<TariffChangeResponse>()

                assertEquals(OrderStatuses.COMPLETED, settled.status)
                assertEquals("tr-max", settled.requestedTariffId)

                // THE ASSERTION THIS FEATURE EXISTS FOR, and the one a naive implementation gets
                // wrong: the change is confirmed and the subscriber is STILL on the old tariff,
                // because it takes effect on a boundary. Both are true at once.
                assertNotEquals("tr-max", settled.currentTariffId, "the change took effect immediately")
                assertTrue(settled.effectiveOnText.isNotBlank(), "the screen cannot say when it happens")
            }
        }

    @Test
    fun `one change at a time, and the second is refused rather than queued`(): Unit =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                client.post(TariffChanges()) {
                    bearerAuth(session.accessToken)
                    setBody(ChangeTariffRequest("tr-max"))
                }

                val second =
                    client
                        .post(TariffChanges()) {
                            bearerAuth(session.accessToken)
                            setBody(ChangeTariffRequest("tr-standard"))
                        }.body<TariffChangeResponse>()

                // Two would race for the same boundary and the later would win by accident of
                // ordering — and a subscriber who asked twice would have no way to know which they
                // got. REJECTED, not COMPENSATED: a rule refused before anything happened.
                assertEquals("rejected", second.status)
            }
        }
}
