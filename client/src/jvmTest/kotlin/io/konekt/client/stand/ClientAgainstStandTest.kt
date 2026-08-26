package io.konekt.client.stand

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.KonektApp
import io.konekt.client.app.KonektDegradation
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.UnknownBlockRenderer
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// THE CLIENT AGAINST THE RUNNING DEPLOYMENT, which is the only place these two halves meet with
// nothing simulated between them.
//
// `:e2e` drives the server over HTTP and asserts about JSON; this drives the client — real screen
// source, real registry, real holder — against the same stand and asserts about what is on SCREEN.
// The difference is one claim: that the text a subscriber reads was composed by the server. A JSON
// assertion cannot tell a field that reached the screen from one a renderer dropped on the way.
//
// It is `:client:standTest` rather than part of `jvmTest`, for the reason `:e2e` carries: a suite
// needing a deployment that is already up would fail every ordinary build on a machine that has not
// started one, and a suite that fails for reasons unrelated to the change is a suite people mute.
@OptIn(ExperimentalTestApi::class)
class ClientAgainstStandTest {
    private val baseUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"

    private fun signedInClient(): HttpClient {
        val session = KonektSession()
        val http = konektHttpClient(CIO.create(), baseUrl, session, konektClientJson)

        runBlocking {
            val msisdn = "1555${(1_000_000..9_999_999).random()}"
            http.post(AuthOtp.Request(AuthOtp())) { setBody(RequestOtpRequest(msisdn)) }
            val code = http.get(DevOtp(msisdn)).body<DevOtpResponse>().code
            val answer = http.post(AuthOtp.Verify(AuthOtp())) { setBody(VerifyOtpRequest(msisdn, code)) }

            val action = konektClientJson.decodeKompotAction(answer.bodyAsText())
            check(action is UpdateSessionAction) { "verify answered ${action::class.simpleName}" }
            session.adopt(SessionTokens(action.accessToken, action.refreshToken))
        }
        return http
    }

    private fun sourceOver(http: HttpClient) =
        KonektScreenSource(
            http = http,
            realtime = SseRealtimeSource(http, konektClientJson),
            registry = konektRegistry(),
            json = konektClientJson,
            onAction = { },
        )

    @Test
    fun `the home screen draws money the client cannot have formatted`() {
        val http = signedInClient()

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = sourceOver(http),
                    address = "/api/v1/screens/home",
                    topic = "stand",
                    darkMode = false,
                )
            }

            // A NEW SUBSCRIBER HAS NOTHING, and `$0` is what the SERVER composes for that — measured
            // against the running stand rather than guessed, which is how this assertion was wrong
            // the first time. The client owns no formatter for money at all (D15), so a formatted
            // amount on this screen can only have been given to it.
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("$0").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("$0").assertIsDisplayed()
            // The label beside it, because an amount alone would also appear on a screen that drew
            // one component and lost the rest.
            onNodeWithText("Balance").assertIsDisplayed()
        }
    }

    @Test
    fun `the brand kit is fetched over HTTP and names the served brand`() {
        // B-22's remaining AC. The two kits have existed as files since that item's first half; what
        // was missing was any way to ASK for one — the endpoint's path had to live in a
        // `*-shared-api` module and there was none.
        val http = signedInClient()
        val theme = runBlocking { sourceOver(http).brandTheme() }

        assertNotNull(theme, "the deployment served no brand kit")
        // The id is what a client resolves a shape scale by, so a kit that arrives without one is a
        // kit that silently falls back to brand A's radii.
        assertEquals("brand-a", theme.id, "the stand serves brand A unless BRAND says otherwise")
        // The light palette, because that is the one every kit must carry: `dark` is nullable by
        // design — a brand that described no dark theme leaves the client on its built-in palette
        // rather than putting dark text on a near-white brand background.
        assertTrue(theme.light.colors.isNotEmpty(), "the kit carries no colours — it would repaint nothing")
    }

    @Test
    fun `every unknown component is recorded, with the type it could not draw`() {
        // B-26's third acceptance criterion, minus where the record lands. Until the sink was bound,
        // an unknown component was drawn correctly and counted by NOTHING — which is the blindness
        // youndie/kompot#81 was filed about, surviving being fixed upstream because konekt never
        // supplied a sink.
        val http = signedInClient()
        val recorded = mutableListOf<KonektDegradation>()

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = sourceOver(http),
                    address = "/api/v1/dev/screens/forward-compat",
                    topic = "stand",
                    darkMode = false,
                    onDegradation = { recorded += it },
                )
            }

            waitUntil(timeoutMillis = 15_000) { recorded.size >= 3 }
        }

        // THREE, and by type AND cause. A count alone would be satisfied by three records of the same
        // wrong thing, and the whole point of `originalType` is answering WHICH type went unrendered.
        assertEquals(3, recorded.size, "expected one record per component that could not be rendered")

        // TWO CAUSES ON ONE SCREEN, which is what this screen exists to put side by side since B-44.
        // The two are the same to a subscriber — same block, same sentence, because "update to see
        // it" is the only move either leaves them — and they must be different to whoever reads the
        // record: one says the client is behind the server, the other says this build shipped a
        // dictionary entry it never wired up.
        val undecodable = recorded.filter { it.cause == KonektDegradation.Cause.UNDECODABLE }
        val undrawable = recorded.filter { it.cause == KonektDegradation.Cause.UNDRAWABLE }

        assertEquals(2, undecodable.size, "expected both esim_transfer_widget blocks: $recorded")
        assertTrue(
            undecodable.all { it.originalType == "esim_transfer_widget" },
            "the records lost the type: ${undecodable.map { it.originalType }}",
        )

        // The one this repository could not report at all until B-44: a type in konekt's own
        // dictionary with no renderer. It reached no block and no sink, and the toolkit's red
        // fallback drew it while nothing counted it.
        assertEquals(1, undrawable.size, "the undrawable component was not recorded: $recorded")
        assertEquals("step_meter", undrawable.single().originalType)

        assertTrue(
            recorded.none { it.drawnAsFallback },
            "a placeholder was reported as a fallback — a hole and a substitution are different facts",
        )
    }

    @Test
    fun `a subscriber who has bought nothing is told so, not shown an error`() {
        // THE FIRST SCREEN EVERY SUBSCRIBER SEES, and no test in this repository had ever looked at
        // it. They all top up and buy before asserting, so the home screen always had a counter and
        // the "no plan is active" banner was never sent — the state a real first-time subscriber gets
        // was the one state nothing exercised.
        //
        // What it actually drew was a red "Unknown component". `banner` is in the dictionary and had
        // no renderer, and a component that DECODES and cannot be DRAWN is not an `UnknownComponent`:
        // it never reaches konekt's degradation block, so the registry's own fallback took it. Found
        // by running the iOS application against the stand with a fresh account.
        val http = signedInClient()

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = sourceOver(http),
                    address = "/api/v1/screens/home",
                    topic = "stand",
                    darkMode = false,
                )
            }

            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("No plan is active on this line yet.").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("No plan is active on this line yet.").assertIsDisplayed()
            // And somewhere to go. A banner that states a fact and offers nothing is the empty screen
            // it was written to replace, with a sentence on it.
            onNodeWithText("See plans").assertIsDisplayed()
        }
    }

    @Test
    fun `an unknown component draws the block, and its neighbours survive`() {
        // B-25's remaining half. Its own test proves both components arrive unknown and both
        // neighbours survive ON THE WIRE; this is the same claim one layer up, where the renderer
        // that has to notice actually runs.
        val http = signedInClient()

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = sourceOver(http),
                    address = "/api/v1/dev/screens/forward-compat",
                    topic = "stand",
                    darkMode = false,
                )
            }

            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("9.7 GB left").fetchSemanticsNodes().isNotEmpty()
            }

            // The known neighbours, above and below. Asserted FIRST, because a screen that drew
            // nothing at all would satisfy "no crash" and satisfy nothing else.
            onNodeWithText("9.7 GB left").assertIsDisplayed()
            onNodeWithText("120 min left").assertIsDisplayed()

            // And the blocks. ONE OF EACH DENSITY, which is B-25's first acceptance criterion and
            // was unreachable until a container decided: the local was declared, read by the
            // renderer, and provided by nothing but the renderer's own test. Both blocks drew the
            // same shape while the screen's comment claimed one of each.
            //
            // Asserted as exact counts rather than "at least one", because one block plus one
            // silently-dropped component is exactly the failure this screen exists to make visible.
            //
            // The copy says what to do rather than what is missing: `originalType` is deliberately
            // NOT on screen — it is a wire name a subscriber cannot act on — so the assertion is on
            // the sentence the canvas specifies.
            assertEquals(
                1,
                onAllNodesWithText(UnknownBlockRenderer.LINE_TEXT).fetchSemanticsNodes().size,
                "the block inside the row did not draw the line density",
            )
            // TWO cards: the second `esim_transfer_widget` and the `step_meter`, which sits in the
            // column too. The step meter is the other CAUSE rather than another density — a type this
            // build decodes and cannot draw — and on screen it is deliberately identical to the block
            // beside it. That identity is the claim: the record tells them apart and the subscriber
            // does not have to.
            assertEquals(
                2,
                onAllNodesWithText(UnknownBlockRenderer.HEADLINE).fetchSemanticsNodes().size,
                "the blocks standing in the column did not draw the card density",
            )
        }
    }
}
