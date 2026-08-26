package io.konekt.e2e

import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.sse.serverSentEvents
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The third scenario, and the one that crosses every process in the build: a purchase grants an
// allowance, the traffic simulator publishes usage to a real broker, a consumer reads the topic and
// decrements the counter, and the new card arrives on an SSE stream that was already open.
//
// Every link is real. A test that wrote the counter directly would prove the arithmetic and nothing
// about the path, and the path is the product.
class LiveUpdateScenarioTest {
    @Test
    fun `a counter that moves reaches an open stream`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.topUp(client, session, majorUnits = 50)

                // A counter has to exist before anything can spend from it: the simulator only
                // publishes for subscribers who have something, because events for anyone else are
                // correctly ignored and a simulator producing only ignored events looks exactly like
                // one that is not running.
                val started =
                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            // THE HOME PLAN, and which side of the roaming branch it lands on is
                            // load-bearing here. Every other plan in the catalogue is a roaming
                            // package and is provisioned DORMANT — so buying one and waiting for a
                            // counter to move waits for something that correctly never happens.
                            setBody(CreatePurchaseRequest("home-20gb-30d"))
                        }.body<PurchaseOrderResponse>()
                client.post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
                    bearerAuth(session.accessToken)
                }

                // `first()` and not an exception thrown out of the block. Ktor wraps anything that
                // escapes `serverSentEvents` into an SSEClientException, so the escape hatch this
                // test first used was caught by the library and re-thrown as a transport error —
                // which reads as "the stream broke" and was in fact "the stream worked".
                var seen: UpdateComponentMessage? = null

                withTimeout(90.seconds) {
                    client.serverSentEvents(
                        urlString = RealtimeStream.PATH,
                        request = { header(HttpHeaders.Authorization, "Bearer ${session.accessToken}") },
                    ) {
                        // One frame is the whole question. The simulator ticks every five seconds,
                        // and a suite that waits for several takes a minute to say the same thing.
                        seen =
                            incoming
                                .mapNotNull { it.data }
                                .map { Stand.json.decodeFromString(UpdateComponentMessage.serializer(), it) }
                                .first()
                    }
                }

                val update =
                    seen ?: error(
                        "the stream produced no frame in 90s — either the simulator is not running, " +
                            "the broker is not carrying it, or the stream is not reaching this " +
                            "subscriber\n\n" + Stand.standDiagnosis(),
                    )

                assertEquals("counter-data", update.componentId)

                val card = update.component as? UsageCounterCardComponent
                assertTrue(card != null, "the frame carried ${update.component::class.simpleName}, not a counter card")
                assertTrue(card.valueText.endsWith("left"), card.valueText)
            }
        }
}
