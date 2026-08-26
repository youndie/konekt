package io.konekt.realtime

import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.konekt.http.SubscriberPrincipal
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

// The live channel, through a real SSE connection.
//
// What is under test is not that a broadcaster works — that is kompot's — but the seam: that a
// component pushed for one subscriber reaches THAT subscriber's open stream, as a frame a client can
// decode, and reaches nobody else's.
class RealtimeStreamTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule = kompotCoreSerializersModule + generatedKonektSerializersModule
        }

    private val broadcaster = KompotUpdateBroadcaster()
    private val push = ComponentBroadcaster(broadcaster, json)

    // A bearer provider standing in for the JWT one. The real provider reads the session family from
    // the database on every request, and dragging that in would test the auth feature a second time.
    // What is kept is the property that matters here: the topic comes from the PRINCIPAL and never
    // from the request.
    private fun Application.testModule(subscriberId: String) {
        install(Koin) { modules(module { single { broadcaster } }) }
        install(SSE)
        install(Authentication) {
            bearer("test") {
                authenticate { SubscriberPrincipal(subscriberId, "family-1") }
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        broadcaster.start(scope)
        monitor.subscribe(ApplicationStopping) { scope.cancel() }

        routing {
            authenticate("test") { realtimeRoutes() }
        }
    }

    private fun card(value: String) = UsageCounterCardComponent(id = "counter-data", title = "Data", valueText = value)

    @Test
    fun `a component pushed for a subscriber arrives on their open stream`() =
        testApplication {
            application { testModule("sub-1") }
            val client = createClient { install(ClientSSE) }

            // The header is not decoration: the route is inside `authenticate`, and without a
            // credential the stream is a 401 rather than an empty stream. That is the right failure
            // and it is worth meeting it here rather than in a client.
            client.sse("/api/v1/realtime", request = { header(HttpHeaders.Authorization, "Bearer anything") }) {
                val sent = card("9975 MB left")

                // PUSHED UNTIL IT LANDS, not once. Opening the client side of an SSE connection does
                // not mean the server's route has run far enough to subscribe, and a push with nobody
                // subscribed is DROPPED rather than queued — which the "a client that goes away is
                // forgotten" test below states outright. So a single push here is a race, and it is
                // one a timeout cannot rescue: the frame is not late, it never existed. It lost on CI
                // for the first time after a commit that touched neither this file nor the broadcaster.
                //
                // Repeating does not weaken the assertion. What is under test is that a component
                // pushed for a subscriber reaches that subscriber's stream as a decodable frame; that
                // the first push had to race the handshake is the harness's problem, not the seam's.
                val event =
                    withTimeout(10.seconds) {
                        val pushing =
                            launch {
                                while (isActive) {
                                    push.push("sub-1", "counter-data", sent)
                                    delay(50)
                                }
                            }
                        incoming.first().also { pushing.cancel() }
                    }
                val message = json.decodeFromString(UpdateComponentMessage.serializer(), event.data!!)

                // The id is what makes an update an update: the client replaces the node it names, so
                // an id that matches nothing on screen is a frame that arrives and changes nothing.
                assertEquals("counter-data", message.componentId)
                assertEquals(sent, message.component)
            }
        }

    @Test
    fun `a stream carries only its own subscriber's updates`() =
        testApplication {
            application { testModule("sub-1") }
            val client = createClient { install(ClientSSE) }

            client.sse("/api/v1/realtime", request = { header(HttpHeaders.Authorization, "Bearer anything") }) {
                // Somebody else's first, every round. If the topic were not per subscriber, theirs
                // would arrive here and the assertion below would read it as ours. Repeated for the
                // reason the test above is: a push before the route has subscribed is dropped, and
                // the ordering that matters is within each round rather than across the run.
                val event =
                    withTimeout(10.seconds) {
                        val pushing =
                            launch {
                                while (isActive) {
                                    push.push("sub-2", "counter-data", card("theirs"))
                                    push.push("sub-1", "counter-data", card("mine"))
                                    delay(50)
                                }
                            }
                        incoming.first().also { pushing.cancel() }
                    }
                val message = json.decodeFromString(UpdateComponentMessage.serializer(), event.data!!)

                assertEquals("mine", (message.component as UsageCounterCardComponent).valueText)
            }
        }

    @Test
    fun `a client that goes away is forgotten`() =
        runBlocking {
            // The subscriber set only shrinks in a `finally`, because the ordinary end of a stream is
            // a closed laptop rather than a graceful close — and a set that only grows is a leak that
            // takes a week to notice.
            val channel = Channel<String>(Channel.UNLIMITED)
            val local = KompotUpdateBroadcaster()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            local.start(scope)

            local.subscribe(RealtimeStream.topicOf("sub-1"), channel)
            local.unsubscribe(RealtimeStream.topicOf("sub-1"), channel)
            ComponentBroadcaster(local, json).push("sub-1", "counter-data", card("x"))

            // The one assertion here about an ABSENCE, so it is bounded rather than awaited: waiting
            // for something that must not come is a hang dressed as a test.
            assertTrue(channel.tryReceive().isFailure)
            scope.cancel()
        }
}
