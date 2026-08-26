package io.konekt.client.stand

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.konekt.client.app.KonektApp
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.auth.shared.api.LOGIN_CODE_DEEPLINK
import io.konekt.feature.auth.shared.api.LOGIN_DEEPLINK
import io.konekt.feature.auth.shared.api.LoginForms
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

// THE WAY IN, DRIVEN THE WAY A SUBSCRIBER DOES IT.
//
// Every other suite in this repository signs in through `/api/v1/dev/otp` — the endpoint that reads
// back a one-time code — because until `B-46` there was nothing else. This one does not know it
// exists: the code comes out of the SMSC mock's LOG, which is where a real one would put it, and the
// only thing this test touches is the screen.
//
// That is the sharpest test of the whole product's claim. A two-step form with two refusals is the
// one screen everybody hand-writes, and if it can be a server response then the boundary is where
// this build says it is. Nothing here types into a component konekt wrote.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LoginStandTest {
    private val baseUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"

    // A form renders through `collectAsStateWithLifecycle`, which collects on the lifecycle's main
    // dispatcher; Skiko's harness provides a frame clock and no `Dispatchers.Main`. A thread of its
    // own rather than `Dispatchers.Default`, because that pool is shared with the requests.
    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "login-main") }

    @BeforeTest
    fun setMain() = Dispatchers.setMain(mainThread.asCoroutineDispatcher())

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
        mainThread.shutdown()
    }

    @Test
    fun `a subscriber signs in through the screen the server built`() {
        val msisdn = "1555${(1_000_000..9_999_999).random()}"
        val session = KonektSession()
        val http = konektHttpClient(CIO.create(), baseUrl, session, konektClientJson)

        val screens =
            KonektScreenSource(
                http = http,
                realtime = SseRealtimeSource(http, konektClientJson),
                registry = konektRegistry(),
                json = konektClientJson,
                submits =
                    mapOf(
                        LoginForms.NUMBER to "/api/v1/auth/login",
                        LoginForms.CODE to "/api/v1/auth/login/code",
                    ),
            )

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = screens,
                    address = "/api/v1/screens/login",
                    topic = "stand",
                    darkMode = false,
                    routes =
                        mapOf(
                            LOGIN_CODE_DEEPLINK to "/api/v1/screens/login/code",
                            LOGIN_DEEPLINK to "/api/v1/screens/login",
                        ),
                    onAction = { action ->
                        if (action is UpdateSessionAction) {
                            session.adopt(SessionTokens(action.accessToken, action.refreshToken))
                            "/api/v1/screens/home"
                        } else {
                            null
                        }
                    },
                )
            }

            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Phone number").performTextInput(msisdn)
            onNodeWithText("Send me a code").performClick()

            // STEP TWO, and the number came with it: verifying needs both halves and this build keeps
            // nothing between the steps, so the screen carries what it was sent to.
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Enter the code").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(msisdn).assertIsDisplayed()

            // A WRONG CODE FIRST, because the refusal is the half a happy path never exercises — and
            // the sentence a subscriber reads for it is composed by the SERVER, like every other
            // string on the screen.
            onNodeWithText("Code").performTextInput("000000")
            onNodeWithText("Sign in").performClick()

            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("That code is wrong or has expired. Ask for a new one.")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            // THE REAL CODE, out of the SMSC mock's log. Not `/api/v1/dev/otp`: this test is what
            // proves the application no longer needs that route, so using it here would prove the
            // opposite.
            val code = smsCodeFor(msisdn)
            onNodeWithText("Code").performTextInput(code)
            onNodeWithText("Sign in").performClick()

            // Home, with the balance the SERVER formatted — a new subscriber has nothing, and `$0` on
            // this screen can only have been given to the client.
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithText("Balance").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("$0").assertIsDisplayed()
        }

        assertTrue(session.tokens.value != null, "the session was never adopted")
    }
}

// THE CODE, OUT OF THE SMSC MOCK'S LOG, which is where a real one would put it.
//
// Reading a container's log is a blunt instrument and it is the honest one here: the alternative is
// `/api/v1/dev/otp`, and this test exists to prove the application no longer needs that route. A mock
// that logs what it "sent" is the closest thing this build has to a phone.
//
// It POLLS rather than reading once: the request that triggers the code is in flight when this is
// called, and a single read races it.
private fun smsCodeFor(msisdn: String): String {
    val pattern = Regex("""otp for \Q$msisdn\E is (\d+)""")

    repeat(40) {
        val logs =
            ProcessBuilder("docker", "compose", "-f", "deploy/compose.yaml", "logs", "--no-color", "server")
                .directory(java.io.File("..").absoluteFile)
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { it.readText() }

        // LAST match and not first: a number that asked twice has two codes in the log, and the one
        // that still verifies is the newer.
        pattern.findAll(logs).lastOrNull()?.let { return it.groupValues[1] }
        Thread.sleep(500)
    }
    error("no code for $msisdn in the server log after 20s — did the SMSC mock stop logging?")
}
