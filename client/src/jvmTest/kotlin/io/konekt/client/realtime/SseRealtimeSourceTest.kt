package io.konekt.client.realtime

import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.konekt.client.net.konektClientJson
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.sse.SSE as ServerSSE

// The transport half of the realtime channel, against a REAL server.
//
// MockEngine was the obvious harness and does not work: the client's SSE plugin never receives a
// frame from it and the collector simply waits, so every assertion times out and none of them says
// why. An embedded CIO server is the transport itself — and CIO is the engine the product runs, which
// is the reason this endpoint chose SSE in the first place.
//
// `io.ktor.client.engine.cio.CIO` and `io.ktor.server.cio.CIO` share a simple name, so both are
// aliased here. Without an alias `embeddedServer` silently takes whichever one the import order gave
// it.
class SseRealtimeSourceTest {
    private val json = konektClientJson

    private fun frame(componentId: String): String =
        json.encodeToString(
            UpdateComponentMessage.serializer(),
            UpdateComponentMessage(
                componentId = componentId,
                component = UsageCounterCardComponent(id = componentId, title = "Data", valueText = "1 GB left"),
            ),
        )

    // Serves one stream per connection, taking the next script each time. A connection that ends is
    // what a dropped network looks like from the client: SSE has no goodbye.
    private fun <T> servingStreams(
        vararg scripts: List<String>,
        block: suspend (source: SseRealtimeSource, connections: AtomicInteger) -> T,
    ): T =
        runBlocking {
            val connections = AtomicInteger(0)
            val server =
                embeddedServer(ServerCIO, port = 0) {
                    install(ServerSSE)
                    routing {
                        sse(RealtimeStream.PATH) {
                            val script = scripts[(connections.getAndIncrement()).coerceAtMost(scripts.lastIndex)]
                            script.forEach { send(ServerSentEvent(data = it)) }
                            // Returning ends the stream, which is the point of the reconnection case.
                        }
                    }
                }.start(wait = false)

            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val client = HttpClient(ClientCIO) { install(SSE) }
                try {
                    block(
                        SseRealtimeSource(
                            client = client,
                            json = json,
                            path = "http://127.0.0.1:$port${RealtimeStream.PATH}",
                            // Short, because the reconnection case waits for it and every other case
                            // never reaches it.
                            backoff = SseRealtimeSource.Backoff(first = 10.milliseconds),
                        ),
                        connections,
                    )
                } finally {
                    client.close()
                }
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }

    @Test
    fun `frames arrive as components`() =
        servingStreams(listOf(frame("counter-data"), frame("counter-minutes"))) { source, _ ->
            val received =
                withTimeout(20.seconds) {
                    source.subscribe("ignored").take(2).toList()
                }

            assertEquals(listOf("counter-data", "counter-minutes"), received.map { it.componentId })
            assertTrue(received.first().component is UsageCounterCardComponent)
        }

    @Test
    fun `a component from a later server arrives as an unknown one rather than not at all`() =
        servingStreams(
            listOf(
                """{"componentId":"x","component":{"type":"esim_transfer_widget","id":"x"}}""",
                frame("counter-data"),
            ),
        ) { source, _ ->
            val received = withTimeout(20.seconds) { source.subscribe("ignored").take(2).toList() }

            // BOTH frames, and the first one is the point. An unfamiliar type decodes to
            // UnknownComponent rather than throwing — that is kompot's degradation, and it means the
            // update is DELIVERED and drawn as the unknown block instead of being lost. Asserting
            // that it was skipped, which is what this test first claimed, would have written the
            // weaker behaviour into the contract.
            assertEquals(listOf("x", "counter-data"), received.map { it.componentId })
            assertTrue(received.first().component is UnknownComponent, "an unfamiliar type did not degrade")
        }

    @Test
    fun `a frame that is not JSON at all is skipped and the stream continues`() =
        servingStreams(listOf("{ this is not json", frame("counter-data"))) { source, _ ->
            // The case the decode guard actually exists for. Taking the stream down here would lose
            // every LATER update as well, for one malformed line.
            val received = withTimeout(20.seconds) { source.subscribe("ignored").take(1).toList() }

            assertEquals(listOf("counter-data"), received.map { it.componentId })
        }

    @Test
    fun `a stream that ends is reconnected, and the gap is announced`() =
        servingStreams(
            listOf(frame("counter-data")),
            listOf(frame("counter-minutes")),
        ) { source, connections ->
            val restarts = mutableListOf<Unit>()

            val received =
                withTimeout(20.seconds) {
                    coroutineScope {
                        val collector = launch { source.streamRestarted.collect { restarts += it } }
                        val frames = source.subscribe("ignored").take(2).toList()
                        collector.cancel()
                        frames
                    }
                }

            assertEquals(listOf("counter-data", "counter-minutes"), received.map { it.componentId })
            assertTrue(connections.get() >= 2, "the stream was not reconnected")
            // THE SIGNAL IS THE POINT, not the reconnection. `Last-Event-ID` would replay what was
            // missed, and this server numbers nothing because an update is losable by design — so
            // what a screen needs after a gap is to refetch, and this is what tells it there was one.
            assertTrue(restarts.isNotEmpty(), "the stream came back and nothing said so")
        }

    @Test
    fun `the backoff doubles and stops at its ceiling`() {
        val backoff = SseRealtimeSource.Backoff(first = 1.seconds, ceiling = 8.seconds)

        assertEquals(1.seconds, backoff.after(0))
        assertEquals(2.seconds, backoff.after(1))
        assertEquals(8.seconds, backoff.after(3))
        // A stream retrying for a day must not overflow the shift, which is what a plain
        // `1 shl attempt` does after thirty-one attempts.
        assertEquals(8.seconds, backoff.after(1_000))
    }
}
