package io.konekt.client.stand

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.KonektRoutes
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.app.Screen
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.client.theme.KonektTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

// B-20'S FIRST ACCEPTANCE CRITERION, and it is the only place it can be held.
//
// "Moving a slider updates the price without the fields losing focus or resetting" is a claim about a
// RENDERED form: that a value the server computed arrived in a component nobody redrew. The server
// test proves the patch carries the right two values; `:e2e` proves the endpoint answers them. Neither
// can tell a price that reached the screen from one that arrived and changed nothing, which is exactly
// the state this build was in until kompot 0.33.0 — `read_only_field` was not bound, so a patch had
// nowhere to land and the only way to show a new price was to fetch the whole form again.
//
// The discriminating assertion is the second one. A refetch would ALSO show the new price; what it
// would not do is leave the chosen quantity standing.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class CustomPackageFormStandTest {
    private val baseUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"

    // A MAIN DISPATCHER, WITHOUT WHICH NO FORM RENDERS IN A TEST AT ALL.
    //
    // The toolkit's bound components read the controller through `collectAsStateWithLifecycle`, which
    // collects on the lifecycle's main dispatcher. Compose's Skiko test harness provides a frame clock
    // and no `Dispatchers.Main`, and `kotlinx-coroutines-test` on the classpath turns the first access
    // into a throw rather than a default. So the form threw before drawing a single field — which is
    // not a defect in the screen and is a precondition of testing one.
    // A THREAD OF ITS OWN, not `Dispatchers.Default`, and the difference is a flake rather than a
    // preference. `Default` is sized to the machine and the patch request runs on it too; on a box
    // that had just finished a full Gradle build this test timed out once in six while passing in
    // under two seconds the rest of the time. A saturated pool starves the collection that carries
    // the new value to the screen, and the failure names the timeout rather than the pool.
    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "form-main") }

    @BeforeTest
    fun setMain() = Dispatchers.setMain(mainThread.asCoroutineDispatcher())

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
        mainThread.shutdown()
    }

    private val formAddress = "/api/v1/forms/custom-package"

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

    @Test
    fun `choosing a quantity reprices the form without redrawing it`() {
        val http = signedInClient()
        val source =
            KonektScreenSource(
                http = http,
                realtime = SseRealtimeSource(http, konektClientJson),
                registry = konektRegistry(),
                json = konektClientJson,
                // THE APPLICATION'S OWN TABLE, and this line is the point of `B-101` rather than a
                // detail of the fixture. This test used to build the patch fetcher itself and hand it
                // to `KonektFormScreen` — so it proved the fetcher works when somebody supplies one,
                // while the application supplied `null` and the price never moved. A test that
                // constructs the collaborator the product forgets is a test of the collaborator.
                patches = KonektRoutes.patches,
            )

        // FETCHED ONCE, outside the composition, and that is load-bearing rather than tidy: a form
        // refetched inside the composable would make "nothing was redrawn" impossible to assert,
        // because the redraw would be this test's doing.
        val response = runBlocking { source.fetchForm(formAddress) }

        runComposeUiTest {
            setContent {
                // The theme is provided by the CALLER, exactly as it is for every other screen:
                // `KonektApp` wraps `screens.render(...)` in `KonektTheme`, and a form is a leaf
                // inside that, not a second place the design system is decided.
                KonektTheme(theme = null, darkMode = false) {
                    // THROUGH THE SOURCE'S OWN `render`, which is the path the application takes.
                    // Calling `KonektFormScreen` directly is what let the wiring rot unnoticed.
                    source.render(Screen.Form(response)) {}
                }
            }

            // The form opens on a package of nothing, which is a real state and the one the server
            // prices at zero. `$0` twice — the price and a new subscriber's balance — so the label is
            // what separates them.
            onNodeWithText("Price").assertIsDisplayed()
            onNodeWithText("Your balance").assertIsDisplayed()

            // Choose 10 GB. The select opens, the option is picked, and the field that moved has
            // triggersPatch — so the controller asks the server what the package now costs.
            onNodeWithText("Data, GB").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("10").fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText("10")[0].performClick()

            // 10 GB at 150 minor units each is $15 — computed by the SERVER and formatted by it, and
            // the client owns no formatter for money at all (D15). A `$15` on this screen can only
            // have been given to it, and only a patch could have put it in a field nobody redrew.
            waitUntil(timeoutMillis = 30_000) {
                onAllNodesWithText("$15").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("$15").assertIsDisplayed()

            // THE HALF A REFETCH WOULD FAIL. The quantity is still 10: the controller survived, the
            // tree was not replaced, and nothing reset to the first step. That is what "without the
            // fields losing focus or resetting" means, and it is the difference between a patch and
            // the refetch this form used to do.
            onNodeWithText("10").assertIsDisplayed()
        }
    }
}
