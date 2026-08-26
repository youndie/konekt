package io.konekt.client.stand

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.KonektApp
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

            // And the blocks. TWO of them, both in the LINE density — deliberately asserted as two
            // rather than as "at least one", because one block plus one silently-dropped component
            // is exactly the failure this screen exists to make visible.
            //
            // The copy says what to do rather than what is missing: `originalType` is deliberately
            // NOT on screen — it is a wire name a subscriber cannot act on — so the assertion is on
            // the sentence the canvas specifies.
            assertEquals(
                2,
                onAllNodesWithText(UnknownBlockRenderer.LINE_TEXT).fetchSemanticsNodes().size,
                "expected both unknown components to draw the line block",
            )
        }
    }
}
