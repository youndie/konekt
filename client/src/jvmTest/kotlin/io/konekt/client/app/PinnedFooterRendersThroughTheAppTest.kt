package io.konekt.client.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.components.CounterStates
import io.konekt.components.SurfaceComponent
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

// THE FOOTER THROUGH THE REAL PATH. The screenshot harness provides a registry around everything it
// draws, so a pinned `surface` above the bar photographed fine — and the first plan page on the
// stand threw `LocalKompotRegistry not provided`: `renderNode` handed shell nodes to the registry
// without putting the registry in scope, which a leaf like the bar never needed and a surface does.
// This drives `KonektApp` with the real `KonektScreenSource` over a real socket, and asks for the
// button the footer carries.
@OptIn(ExperimentalTestApi::class)
class PinnedFooterRendersThroughTheAppTest {
    @Test
    fun `a pinned surface is drawn above the bar through the real source`() {
        val tree =
            ColumnComponent(
                id = "plan",
                children =
                    listOf(
                        TextComponent(id = "title", text = "Turkey · 10 GB · 30 days"),
                        SurfaceComponent(
                            id = "footer",
                            pinned = true,
                            children =
                                listOf(
                                    TextComponent(id = "charged", text = "Charged once"),
                                    ButtonComponent(
                                        id = "buy",
                                        text = "Buy for $12",
                                        action = NavigateAction("app://nowhere"),
                                    ),
                                ),
                        ),
                    ),
            )

        val server =
            embeddedServer(ServerCIO, port = 0) {
                install(ServerSSE)
                routing {
                    get("/api/v1/screens/plans/tr") {
                        call.respondText(konektClientJson.encodeKompotComponent(tree), ContentType.Application.Json)
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
                )

            runComposeUiTest {
                setContent {
                    KonektApp(
                        screens = screens,
                        address = "/api/v1/screens/plans/tr",
                        topic = "test",
                        darkMode = false,
                    )
                }
                waitUntil(
                    timeoutMillis = 5_000,
                ) { onAllNodesWithText("Buy for $12").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Charged once").assertIsDisplayed()
                onNodeWithText("Turkey · 10 GB · 30 days").assertIsDisplayed()
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }
}
