package io.konekt.client.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.sse.SSE as ServerSSE

// THE WIRING FROM A RESPONSE TO A PIXEL, against a real socket and a real serialiser.
//
// `KonektAppTest` beside this drives the holder with a fake source, because what it checks is an
// ORDER of operations. This checks the other half — that the source really decodes what a server
// really sent and the holder really draws it — and it is here rather than in `:e2e` because the
// question is about this module and needs no stand.
//
// An embedded server rather than MockEngine: the client tests learned that the hard way for SSE,
// where MockEngine and the SSE plugin never meet and the collector simply waits. The same engine the
// product uses is the only thing that proves the wiring compiles against an engine that exists.
@OptIn(ExperimentalTestApi::class)
class KonektScreenSourceTest {
    @Test
    fun `a tree the server sent is decoded and drawn, text and all`() {
        val tree =
            ColumnComponent(
                id = "home",
                children =
                    listOf(
                        TextComponent(id = "balance", text = "38.00 $"),
                        UsageCounterCardComponent(
                            id = "counter-data",
                            title = "Data",
                            valueText = "9.7 GB left",
                            state = CounterStates.NORMAL,
                            progress = 0.5f,
                        ),
                    ),
            )

        val server =
            embeddedServer(ServerCIO, port = 0) {
                install(ServerSSE)
                routing {
                    get("/api/v1/screens/home") {
                        // Encoded the way the server encodes it, discriminator and all. A hand-written
                        // JSON string here would test this test's idea of the wire.
                        call.respondText(
                            konektClientJson.encodeKompotComponent(tree),
                            ContentType.Application.Json,
                        )
                    }
                }
            }.start(wait = false)

        val port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }

        try {
            val http = konektHttpClient(CIO.create(), "http://127.0.0.1:$port", KonektSession(), konektClientJson)
            val screens =
                KonektScreenSource(
                    http = http,
                    realtime = SseRealtimeSource(http, konektClientJson),
                    registry = konektRegistry(),
                    json = konektClientJson,
                    onAction = { },
                )

            runComposeUiTest {
                setContent {
                    KonektApp(
                        screens = screens,
                        address = "/api/v1/screens/home",
                        topic = "test",
                        darkMode = false,
                    )
                }
                // `waitUntil` AND NOT `waitForIdle`. The holder fetches in a LaunchedEffect over a
                // real socket, and idleness is about composition rather than about somebody else's
                // suspending call — `waitForIdle` returns while the request is still in flight and
                // the assertion then fails on an empty tree. Measured: the fetch above proves the
                // wire, so the first failure here was the harness and not the code.
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("38.00 $").fetchSemanticsNodes().isNotEmpty() }

                // BOTH, and the counter is the one that matters: its text was composed by the server
                // — the client has no formatter for money or for gigabytes, deliberately (D15), so a
                // client drawing "9.7 GB left" can only have been given it.
                onNodeWithText("38.00 $").assertIsDisplayed()
                onNodeWithText("9.7 GB left").assertIsDisplayed()
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }
}
