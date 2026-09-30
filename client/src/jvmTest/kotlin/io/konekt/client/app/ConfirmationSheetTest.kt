package io.konekt.client.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.navigation.PresentationHeader
import io.github.youndie.kompot.navigation.ScreenRoutePresentation
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.feature.purchase.shared.api.BuyPlanAction
import io.konekt.feature.purchase.shared.api.ConfirmPurchaseAction
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.sse.SSE as ServerSSE

// THE CONFIRMATION AS A SHEET OVER THE PLAN PAGE (`B-116`), from the header to the pixel.
//
// A real socket and the real source, because the subject starts at a RESPONSE HEADER: the order
// screen asks to be a sheet beside its body, `KonektScreenSource` reads it, the holder lays the tree
// over the page through kompot's chain, and `KonektSheetHost` draws it. A fake source would hand the
// holder a presentation the source never had to read — the half of this most likely to be wrong.
//
// The server is a stand-in for the order screen's two states: waiting for a confirmation (asks for a
// sheet) and paid (asks for nothing), at ONE address, which is the whole reason the question travels
// on the answer rather than on a route.
@OptIn(ExperimentalTestApi::class)
class ConfirmationSheetTest {
    private val confirmed = AtomicBoolean(false)
    private val planFetches = AtomicInteger(0)
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0

    private fun column(
        id: String,
        vararg children: KompotComponent,
    ) = ColumnComponent(id = id, children = children.toList())

    private suspend fun ApplicationCall.respondTree(tree: KompotComponent) =
        respondText(konektClientJson.encodeKompotComponent(tree), ContentType.Application.Json)

    @BeforeTest
    fun start() {
        server =
            embeddedServer(ServerCIO, port = 0) {
                install(ServerSSE)
                routing {
                    get("/plan") {
                        planFetches.incrementAndGet()
                        call.respondTree(
                            column(
                                "plan",
                                TextComponent(id = "plan-title", text = "the plan page"),
                                ButtonComponent(id = "buy", text = "Buy", action = BuyPlanAction("tr-10gb-30d")),
                            ),
                        )
                    }
                    get("/order") {
                        if (confirmed.get()) {
                            // THE RESULT, at the same address, with no header — which is what the
                            // server sends for every state but the confirmation.
                            call.respondTree(column("result", TextComponent(id = "paid", text = "Paid")))
                        } else {
                            call.response.header(PresentationHeader.HEADER_NAME, ScreenRoutePresentation.SHEET)
                            call.respondTree(
                                column(
                                    "confirm",
                                    TextComponent(id = "confirm-title", text = "Confirm purchase"),
                                    ButtonComponent(id = "pay", text = "Pay", action = ConfirmPurchaseAction("o-1")),
                                    ButtonComponent(id = "not-now", text = "Not now", action = NavigateAction(HOME)),
                                ),
                            )
                        }
                    }
                    // A word this client has never heard, from a newer server: the answer said
                    // nothing it can use, so it is a screen.
                    get("/odd") {
                        call.response.header(PresentationHeader.HEADER_NAME, "drawer")
                        call.respondTree(column("odd", TextComponent(id = "odd-title", text = "odd")))
                    }
                    get("/home") {
                        call.respondTree(column("home", TextComponent(id = "home-title", text = "the home screen")))
                    }
                }
            }.start(wait = false)
        port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
    }

    @AfterTest
    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    private fun source(presentations: Set<String> = KonektSheetHost.DRAWS): KonektScreenSource {
        val http = konektHttpClient(CIO.create(), "http://127.0.0.1:$port", KonektSession(), konektClientJson)
        return KonektScreenSource(
            http = http,
            realtime = SseRealtimeSource(http, konektClientJson),
            registry = konektRegistry(),
            json = konektClientJson,
            presentations = presentations,
        )
    }

    // What the runner does with the two purchase actions, minus the HTTP: both answer with the
    // order's address, and confirming moves the order to its paid state first.
    private val runner: suspend (KompotAction) -> Destination? = { action ->
        when (action) {
            is BuyPlanAction -> {
                Destination.next("/order")
            }

            is ConfirmPurchaseAction -> {
                confirmed.set(true)
                Destination.next("/order")
            }

            else -> {
                null
            }
        }
    }

    private fun ComposeUiTest.openThePlanPage(screens: KonektScreenSource) {
        setContent {
            KonektApp(
                screens = screens,
                address = "/plan",
                topic = "test",
                darkMode = false,
                routes = mapOf(HOME to "/home"),
                onAction = runner,
            )
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("the plan page").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.buy() {
        onNodeWithText("Buy").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Confirm purchase").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.sheetIsOpen(): Boolean =
        onAllNodesWithTag(KonektSheetHost.SHEET_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForTheSheetToClose() = waitUntil(timeoutMillis = 10_000) { !sheetIsOpen() }

    @Test
    fun `the source reads the answer's presentation and only one it can draw`() =
        runBlocking {
            assertEquals(ScreenRoutePresentation.SHEET, (source().fetch("/order") as Screen.Tree).presentation)
            assertEquals(ScreenRoutePresentation.SCREEN, (source().fetch("/plan") as Screen.Tree).presentation)
            assertEquals(ScreenRoutePresentation.SCREEN, (source().fetch("/odd") as Screen.Tree).presentation)
            // THE CLIENT THAT PREDATES THE SHEET, against the same answer: a screen.
            assertEquals(
                ScreenRoutePresentation.SCREEN,
                (source(setOf(ScreenRoutePresentation.SCREEN)).fetch("/order") as Screen.Tree).presentation,
            )
        }

    @Test
    fun `the confirmation opens over the plan page and the page stays mounted`() =
        runComposeUiTest {
            openThePlanPage(source())
            buy()

            assertTrue(sheetIsOpen(), "the confirmation arrived and no sheet was drawn")
            onNodeWithContentDescription(KonektSheetHost.HANDLE).assertIsDisplayed()
            // UNDER IT, the page it was opened from — still there, never fetched again.
            onNodeWithText("the plan page").assertIsDisplayed()
            assertEquals(1, planFetches.get(), "the plan page was fetched again under the sheet")
            // And the stack did not move: the page was the root, so there is no way back from it.
            assertEquals(
                0,
                onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size,
                "opening the sheet moved the stack — the confirmation became a screen with a back control",
            )
        }

    @Test
    fun `a tap on the scrim closes the sheet and leaves the plan page where it was`() =
        runComposeUiTest {
            openThePlanPage(source())
            buy()

            // AT THE TOP of the window, above the sheet: the scrim covers everything and the sheet
            // covers the bottom of it, so a click at the centre could land on either.
            onNodeWithContentDescription(KonektSheetHost.SCRIM).performTouchInput { click(Offset(centerX, 10f)) }
            waitForTheSheetToClose()

            onNodeWithText("the plan page").assertIsDisplayed()
            assertEquals(0, onAllNodesWithText("Confirm purchase").fetchSemanticsNodes().size)
            assertEquals(1, planFetches.get(), "dismissing refetched the plan page instead of uncovering it")
            assertTrue(!confirmed.get(), "dismissing confirmed the order")
        }

    @Test
    fun `a drag down closes the sheet`() =
        runComposeUiTest {
            openThePlanPage(source())
            buy()

            onNodeWithContentDescription(KonektSheetHost.HANDLE).performTouchInput {
                swipeDown(startY = centerY, endY = centerY + 400f, durationMillis = 300)
            }
            waitForTheSheetToClose()

            onNodeWithText("the plan page").assertIsDisplayed()
            assertTrue(!confirmed.get(), "dragging the sheet away confirmed the order")
        }

    // THE CONTROL FOR THE ONE ABOVE: a nudge is not a dismissal. Without it, a sheet that closed on
    // ANY touch of the handle would pass the drag test.
    @Test
    fun `a short slow drag lets the sheet spring back`() =
        runComposeUiTest {
            openThePlanPage(source())
            buy()

            onNodeWithContentDescription(KonektSheetHost.HANDLE).performTouchInput {
                swipeDown(startY = centerY, endY = centerY + 30f, durationMillis = 1_000)
            }
            waitForIdle()

            assertTrue(sheetIsOpen(), "a 30-pixel nudge closed the sheet")
        }

    // `Pay` is konekt's own action and the runner answers it with an address, not a `navigate` — so
    // kompot's `withOverlays` never sees a move, and without the holder's own close the sheet would
    // sit over the result.
    @Test
    fun `pay leaves no sheet behind`() =
        runComposeUiTest {
            openThePlanPage(source())
            buy()

            onNodeWithText("Pay").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Paid").fetchSemanticsNodes().isNotEmpty() }

            assertTrue(!sheetIsOpen(), "the sheet is still open over the paid result")
            assertEquals(0, onAllNodesWithText("Confirm purchase").fetchSemanticsNodes().size)
        }

    // `Not now` is a `navigate`, and that one kompot closes in the chain before the move (its `B-67`).
    @Test
    fun `not now closes the sheet and goes where it says`() =
        runComposeUiTest {
            openThePlanPage(source())
            buy()

            onNodeWithText("Not now").performClick()
            waitUntil(
                timeoutMillis = 10_000,
            ) { onAllNodesWithText("the home screen").fetchSemanticsNodes().isNotEmpty() }

            assertTrue(!sheetIsOpen(), "the sheet is still open over the screen `Not now` went to")
            assertTrue(!confirmed.get())
        }

    // THE CLIENT THAT DOES NOT DRAW A SHEET gets the confirmation as it always did: a screen of its
    // own, replacing the plan page, with nothing laid over anything.
    @Test
    fun `a client without a sheet shows the confirmation as a screen`() =
        runComposeUiTest {
            openThePlanPage(source(setOf(ScreenRoutePresentation.SCREEN)))
            buy()

            assertTrue(!sheetIsOpen(), "a client that draws no sheet drew one")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("the plan page").fetchSemanticsNodes().isEmpty() }
            onNodeWithText("Pay").assertIsDisplayed()
        }

    private companion object {
        const val HOME = "app://home"
    }
}
