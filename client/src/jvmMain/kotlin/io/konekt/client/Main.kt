package io.konekt.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.KonektApp
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.observability.KonektClientObservability
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.PlansScreenResource
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.konekt.time.SystemClock
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer

// A RUNNER AGAINST THE STAND, not the shipped application, and the difference is one route.
//
// It signs in through `/api/v1/dev/otp`, the development endpoint that reads back a one-time code.
// That route exists only where `DEV_REVEAL_OTP` is set and must never ship: a machine endpoint
// revealing any subscriber's code IS the authentication system. A real client draws a login screen
// and the subscriber types the code they were sent.
//
// What it is for is the other half of B-43's first acceptance criterion — a window drawing a home
// screen the SERVER built, counter cards included, with no text in it composed here:
//
//     make stand-up && ./gradlew :client:run
fun main() {
    val baseUrl = System.getenv("KONEKT_URL") ?: "http://127.0.0.1:8080"
    val session = KonektSession()
    val http = konektHttpClient(CIO.create(), baseUrl, session, konektClientJson)

    // Blocking, and deliberately before the window opens. A runner that draws an empty frame and then
    // fills it is demonstrating a loading state nobody asked for; the holder itself fetches inside the
    // composition, which is the product's behaviour.
    runBlocking {
        val msisdn = "1555${(1_000_000..9_999_999).random()}"
        http.post(AuthOtp.Request(AuthOtp())) { setBody(RequestOtpRequest(msisdn)) }
        val code = http.get(DevOtp(msisdn)).body<DevOtpResponse>().code
        val answer = http.post(AuthOtp.Verify(AuthOtp())) { setBody(VerifyOtpRequest(msisdn, code)) }

        val action = konektClientJson.decodeKompotAction(answer.bodyAsText())
        check(action is UpdateSessionAction) { "verify answered ${action::class.simpleName}, not a session" }
        session.adopt(SessionTokens(action.accessToken, action.refreshToken))
    }

    val screens =
        KonektScreenSource(
            http = http,
            realtime = SseRealtimeSource(http, konektClientJson),
            registry = konektRegistry(),
            json = konektClientJson,
        )

    // OBSERVABILITY, and the runner is where it is decided rather than inside the holder. A client
    // reports to whatever its deployment was built against, and a library that picked its own
    // collector would be a library nobody could point somewhere else.
    //
    // Both variables absent is a decision — no tracy, and the katcher breadcrumb still works. One
    // absent is refused, because a build that meant to be observed and is silent looks exactly like
    // one that is working.
    val observabilityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val observability =
        KonektClientObservability.of(
            endpoint = System.getenv("TRACY_ENDPOINT"),
            apiKey = System.getenv("TRACY_KEY"),
            release = System.getenv("KONEKT_RELEASE") ?: "desktop-dev",
            instanceId = System.getenv("HOSTNAME") ?: "desktop",
            scope = observabilityScope,
            clock = SystemClock,
        )
    observability.start()

    application {
        Window(onCloseRequest = ::exitApplication, title = "konekt") {
            KonektApp(
                screens = screens,
                // THE SINK'S OUTPUT, which was `RECORDS_NOTHING` until tracy could be reached from
                // every platform this client builds for. An unknown component was drawn correctly and
                // counted nowhere — the exact blindness kompot#81 was filed about, surviving its own
                // fix upstream.
                onDegradation = observability.recorder(),
                address = homeAddress(),
                // A LOCAL KEY, NOT AN ADDRESS, and that is worth knowing before somebody goes looking
                // for where it is sent. `SseRealtimeSource.subscribe` ignores its topic: the path is
                // fixed and the SERVER derives the topic from the caller's token. So this string only
                // keys the overlay map — and it has to be something, because the client cannot learn
                // its own subscriber id at all. `UpdateSessionAction` does not carry one, which is why
                // the e2e stand reads it out of the database.
                topic = "konekt-session",
                darkMode = false,
                // THE ONE TRANSITION THIS BUILD HAS. The home screen's banner offers "See plans" and
                // the deeplink is spelled once, in the shared module both sides read.
                routes = mapOf(PLANS_DEEPLINK to plansAddress()),
                // Announced rather than swallowed: a handler that silently did nothing would make a
                // button that does nothing indistinguishable from one whose handler is missing.
                onAction = { action -> println("konekt: no handler for $action") },
            )
        }
    }
}

// The address, derived from the `@Resource` rather than typed again. The three lines are a copy of
// `io.konekt.openapi.ResourceAddresses`; the ADDRESS is not — it is still spelled once, in the
// annotation. The server's copy lives in `:server`, which a client cannot see.
private fun homeAddress(): String = addressOf<HomeScreenResource>()

private fun plansAddress(): String = addressOf<PlansScreenResource>()

// Derived from the `@Resource` rather than typed again. The ADDRESS is still spelled once, in the
// annotation; this is a copy of the three lines in `io.konekt.openapi.ResourceAddresses`, which lives
// in `:server` and a client cannot see.
private inline fun <reified T : Any> addressOf(): String =
    ResourcesFormat()
        .encodeToPathPattern(serializer<T>())
        .let { if (it.startsWith("/")) it else "/$it" }
