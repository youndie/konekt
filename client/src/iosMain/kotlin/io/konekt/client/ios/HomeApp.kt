package io.konekt.client.ios

import androidx.compose.ui.window.ComposeUIViewController
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.BuyPlan
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
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

// THE APPLE HALF OF B-43'S COMPOSITION ROOT: an iOS application that draws the home screen the SERVER
// built, through the same holder, source and registry the desktop runner uses.
//
// The desktop runner proved the root works; this proves it works on the platform the product is
// actually for. Nothing about the holder is duplicated here — `KonektApp` is the same composable, and
// what this file adds is the twelve lines of platform between a `@Composable` and a phone.
//
// A RUNNER AND NOT THE PRODUCT, exactly like `Main.kt` and for the same reason: it signs in through
// `/api/v1/dev/otp`, the development endpoint that reads back a one-time code. A machine endpoint
// revealing any subscriber's code IS the authentication system; a real client draws a login screen
// and the subscriber types what they were sent.
@OptIn(ExperimentalForeignApi::class)
fun homeViewController(): UIViewController {
    val env = NSProcessInfo.processInfo.environment

    fun setting(name: String): String = (env[name] as? String).orEmpty()

    // ON THE SIMULATOR, `127.0.0.1` IS THE HOST MAC. That is why this needs no address of its own by
    // default and why a device would: a phone on a desk has no route to a laptop's loopback.
    val baseUrl = setting("KONEKT_URL").ifBlank { "http://127.0.0.1:8080" }

    val session = KonektSession()
    val http = konektHttpClient(Darwin.create(), baseUrl, session, konektClientJson)

    // Blocking, and deliberately before the view exists — the same choice the desktop runner makes. A
    // runner that draws an empty frame and then fills it is demonstrating a loading state nobody asked
    // for; the holder itself fetches inside the composition, which is the product's behaviour.
    runBlocking {
        val msisdn = "1555${(1_000_000..9_999_999).random()}"
        http.post(AuthOtp.Request(AuthOtp())) { setBody(RequestOtpRequest(msisdn)) }
        val code = http.get(DevOtp(msisdn)).body<DevOtpResponse>().code
        val answer = http.post(AuthOtp.Verify(AuthOtp())) { setBody(VerifyOtpRequest(msisdn, code)) }

        val action = konektClientJson.decodeKompotAction(answer.bodyAsText())
        check(action is UpdateSessionAction) { "verify answered ${action::class.simpleName}, not a session" }
        session.adopt(SessionTokens(action.accessToken, action.refreshToken))
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val observability =
        KonektClientObservability.of(
            endpoint = setting("TRACY_ENDPOINT").ifBlank { null },
            apiKey = setting("TRACY_KEY").ifBlank { null },
            release = setting("KONEKT_RELEASE").ifBlank { "ios-dev" },
            instanceId = "simulator",
            scope = scope,
            clock = SystemClock,
        )
    observability.start()

    val buy = BuyPlan(http)
    val screens =
        KonektScreenSource(
            http = http,
            realtime = SseRealtimeSource(http, konektClientJson),
            registry = konektRegistry(),
            json = konektClientJson,
        )

    return ComposeUIViewController {
        KonektApp(
            screens = screens,
            address = homeAddress(),
            // A LOCAL KEY, NOT AN ADDRESS. `SseRealtimeSource.subscribe` ignores its topic: the path
            // is fixed and the SERVER derives the topic from the caller's token, so this only keys
            // the overlay map. The client cannot learn its own subscriber id at all.
            topic = "konekt-ios",
            darkMode = false,
            routes = mapOf(PLANS_DEEPLINK to plansAddress()),
            onAction = { action ->
                // BUYING IS HANDLED HERE and not in the holder: a screen holder with an opinion
                // about purchases is this application's holder rather than a reusable one. What
                // comes back is the order screen's address, and the holder moves to it exactly as
                // it moves for a `navigate`.
                buy.addressFor(action) ?: run {
                    println("konekt-ios: no handler for $action")
                    null
                }
            },
            onDegradation = observability.recorder(),
        )
    }
}

// The address, derived from the `@Resource` rather than typed again — the same three lines the
// desktop runner has, and the ADDRESS itself is still spelled once, in the annotation.
private fun homeAddress(): String = addressOf<HomeScreenResource>()

private fun plansAddress(): String = addressOf<PlansScreenResource>()

// Derived from the `@Resource` rather than typed again. The ADDRESS is still spelled once, in the
// annotation; this is a copy of the three lines in `io.konekt.openapi.ResourceAddresses`, which lives
// in `:server` and a client cannot see.
private inline fun <reified T : Any> addressOf(): String =
    ResourcesFormat()
        .encodeToPathPattern(serializer<T>())
        .let { if (it.startsWith("/")) it else "/$it" }
