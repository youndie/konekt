package io.konekt.client.ios

import androidx.compose.ui.window.ComposeUIViewController
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.BuyPlan
import io.konekt.client.app.Destination
import io.konekt.client.app.KonektApp
import io.konekt.client.app.KonektRoutes
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.observability.KonektClientObservability
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.esim.shared.api.ESIM_INSTALL_DEEPLINK
import io.konekt.feature.esim.shared.api.EsimInstallScreenResource
import io.konekt.time.SystemClock
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

// THE APPLE HALF OF B-43'S COMPOSITION ROOT: an iOS application that draws the home screen the SERVER
// built, through the same holder, source and registry the desktop runner uses.
//
// The desktop runner proved the root works; this proves it works on the platform the product is
// actually for. Nothing about the holder is duplicated here — `KonektApp` is the same composable, and
// what this file adds is the twelve lines of platform between a `@Composable` and a phone.
//
// IT OPENS ON THE LOGIN SCREEN, like the desktop one and for the same reason: both used to sign in
// through `/api/v1/dev/otp` — the endpoint that reads back a one-time code — and both said in their
// own comments what that is. `B-46` built the way in, so neither knows the development route exists.
@OptIn(ExperimentalForeignApi::class)
fun homeViewController(): UIViewController {
    val env = NSProcessInfo.processInfo.environment

    fun setting(name: String): String = (env[name] as? String).orEmpty()

    // ON THE SIMULATOR, `127.0.0.1` IS THE HOST MAC. That is why this needs no address of its own by
    // default and why a device would: a phone on a desk has no route to a laptop's loopback.
    val baseUrl = setting("KONEKT_URL").ifBlank { "http://127.0.0.1:8080" }

    val session = KonektSession()
    val http = konektHttpClient(Darwin.create(), baseUrl, session, konektClientJson)

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
            submits = KonektRoutes.submits,
        )

    return ComposeUIViewController {
        KonektApp(
            screens = screens,
            address = KonektRoutes.loginAddress,
            // A LOCAL KEY, NOT AN ADDRESS. `SseRealtimeSource.subscribe` ignores its topic: the path
            // is fixed and the SERVER derives the topic from the caller's token, so this only keys
            // the overlay map. The client cannot learn its own subscriber id at all.
            topic = "konekt-ios",
            darkMode = false,
            // THE SAME TABLE THE DESKTOP RUNNER USES, which it was not: this one knew three
            // deeplinks against the other's six, so the tabs and the two flows below moved on
            // one platform and printed "no handler" on the other.
            routes = KonektRoutes.map,
            onAction = { action ->
                when {
                    // SIGNING IN, and this runner could not. It imported `UpdateSessionAction` and
                    // `SessionTokens`, held a `KonektSession`, opened on the login screen — and
                    // handled neither, so the second step answered an action nothing adopted and the
                    // application printed "no handler" and stayed where it was. The iOS build could
                    // not get past its first screen, and nothing said so: the imports compiled, the
                    // session object was constructed, and every part existed except the branch.
                    action is UpdateSessionAction -> {
                        session.adopt(SessionTokens(action.accessToken, action.refreshToken))
                        // START OVER: the login screen is the bottom of this stack, so replacing the
                        // top would leave it under the home screen and put a back control on a tab.
                        Destination.startOver(KonektRoutes.homeAddress)
                    }

                    // BUYING IS HANDLED HERE and not in the holder: a screen holder with an opinion
                    // about purchases is this application's holder rather than a reusable one. What
                    // comes back is the order screen's address, and the holder moves to it exactly
                    // as it moves for a `navigate`.
                    else -> {
                        buy.addressFor(action)?.let(Destination::next) ?: run {
                            println("konekt-ios: no handler for $action")
                            null
                        }
                    }
                }
            },
            onDegradation = observability.recorder(),
        )
    }
}
