package io.konekt.client.app

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.observability.KonektClientObservability
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.auth.shared.api.AuthSession
import io.konekt.feature.shell.shared.api.SignOutAction
import io.konekt.time.SystemClock
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.resources.post
import kotlinx.coroutines.CoroutineScope

// THE COMPOSITION ROOT, ONCE, FOR EVERY PLATFORM THIS CLIENT RUNS ON.
//
// It was written three times before this — desktop, iOS, and Android would have been the third — and
// the copies had already diverged in the way copies do: the desktop runner handled `SignOutAction`
// and the iOS one did not, so signing out worked on a laptop and did nothing on a phone. Nothing
// failed. The action reached a `when` with no branch for it, printed "no handler" to a log nobody
// reads on a device, and left the subscriber on the profile screen with a live session.
//
// `B-85` is the item that would have made a third copy, so it is the item that stops there being
// copies. What a platform actually differs in is four things — an HTTP engine, where settings come
// from, what to call itself, and how a `@Composable` reaches a screen — and all four are parameters.
//
// WHAT IS DELIBERATELY NOT HERE: the window, the activity, the view controller. A composition root is
// not an application, and the twelve lines of platform between a `@Composable` and a screen belong to
// the platform that has them.
class KonektComposition(
    engine: HttpClientEngine,
    // WHERE A SETTING COMES FROM. `System.getenv` on the JVM, `NSProcessInfo` on iOS, and on Android
    // the manifest's metadata or a build field — a phone has no environment to read.
    private val settings: (String) -> String?,
    private val platform: KonektPlatform,
    scope: CoroutineScope,
) {
    private val session = KonektSession()

    private val http =
        konektHttpClient(
            engine,
            settings("KONEKT_URL")?.ifBlank { null } ?: platform.defaultBaseUrl,
            session,
            konektClientJson,
        )

    // OBSERVABILITY IS DECIDED HERE AND NOT INSIDE THE HOLDER. A client reports to whatever its
    // deployment was built against, and a holder that picked its own collector would be a holder
    // nobody could point somewhere else.
    //
    // Both settings absent is a decision — no tracy, and the katcher breadcrumb still works. One
    // absent is refused, because a build that meant to be observed and is silent looks exactly like
    // one that is working.
    private val observability =
        KonektClientObservability.of(
            endpoint = settings("TRACY_ENDPOINT")?.ifBlank { null },
            apiKey = settings("TRACY_KEY")?.ifBlank { null },
            release = settings("KONEKT_RELEASE")?.ifBlank { null } ?: platform.defaultRelease,
            instanceId = settings("KONEKT_INSTANCE")?.ifBlank { null } ?: platform.name,
            scope = scope,
            clock = SystemClock,
        )

    private val buy = BuyPlan(http)

    private val install = EsimInstall(http, konektClientJson)
    private val resend = ResendCode(http, konektClientJson)

    private val screens =
        KonektScreenSource(
            http = http,
            realtime = SseRealtimeSource(http, konektClientJson),
            // ONE REGISTRY. A second renderer map is how two platforms end up drawing the same wire
            // type differently, and it is the thing the multiplatform claim is actually about.
            registry = konektRegistry(),
            json = konektClientJson,
            submits = KonektRoutes.submits,
            patches = KonektRoutes.patches,
        )

    fun start() {
        observability.start()
    }

    @Composable
    fun Screen() {
        KonektApp(
            screens = screens,
            // OPENS ON THE LOGIN SCREEN. There is no session yet, and the home screen behind a token
            // would answer 401 to an application that has not asked for one.
            address = KonektRoutes.loginAddress,
            // A LOCAL KEY, NOT AN ADDRESS. `SseRealtimeSource.subscribe` ignores its topic: the path
            // is fixed and the SERVER derives the topic from the caller's token, so this only keys the
            // overlay map. The client cannot learn its own subscriber id at all.
            topic = platform.topicKey,
            // A CLIENT SETTING, and the profile screen says why: which palette to draw is decided
            // where the drawing happens, so a server-driven row for it would be this product's one
            // piece of state the server holds and cannot act on.
            darkMode = settings("KONEKT_DARK") == "true",
            // THE BOOTSTRAP, and not a route table: the two destinations reachable before there is a
            // session. Everything else arrives from the graph the server publishes.
            routes = KonektRoutes.bootstrap,
            onDegradation = observability.recorder(),
            onAction = ::handle,
        )
    }

    // EVERY ACTION THE HOLDER CANNOT ANSWER ITSELF, in one place because the failure of having it in
    // three was silent on two of them.
    private suspend fun handle(action: KompotAction): Destination? =
        when {
            // THE SESSION LANDS HERE, which is the one thing a holder must not do: it would be a
            // screen holder that knows what a token is.
            action is UpdateSessionAction -> {
                session.adopt(SessionTokens(action.accessToken, action.refreshToken))
                // START OVER: the login screen is the bottom of this stack, so replacing the top
                // would leave it under the home screen and put a back control on a tab.
                Destination.startOver(KonektRoutes.homeAddress)
            }

            // LEAVING, and the ORDER is the whole of it: tell the server first, while the token is
            // still usable, then drop the tokens. The other way round the request goes out
            // unauthenticated and the refresh family lives on — a subscriber who signed out on a
            // shared machine and a session that did not end. The answer is not waited on for
            // correctness, only for order: if the call fails the tokens still go, because the one
            // thing that must not happen is a screen saying "signed out" over a live session.
            action is SignOutAction -> {
                suspendRunCatching { http.post(AuthSession.Logout()) }
                session.clear()
                // The same boundary from the other side, and worse if it is missed: back from here
                // would return to a screen whose token this line just dropped.
                Destination.startOver(KonektRoutes.loginAddress)
            }

            // BUYING AND THE WIZARD are handled here and not in the holder: a screen holder with an
            // opinion about purchases is this application's holder rather than a reusable one.
            else -> {
                // `install` answers a DESTINATION and its two siblings answer addresses, because
                // finishing the wizard is the one action here that leaves its flow rather than moving
                // within it (`B-76`).
                (
                    buy.addressFor(action)?.let(Destination::next)
                        ?: install.destinationFor(action)
                        ?: resend.addressFor(action)?.let(Destination::next)
                )
                    ?: run {
                        // Announced rather than swallowed: a handler that silently did nothing makes
                        // a button that does nothing indistinguishable from a missing branch.
                        println("${platform.name}: no handler for $action")
                        null
                    }
            }
        }
}

// What one platform is called, and the three things that follow from the name.
//
// A DATA CLASS AND NOT AN ENUM, because the client library must not carry a list of the applications
// built on it: the list is the thing that goes stale when a fourth is added somewhere else.
data class KonektPlatform(
    val name: String,
    // ON A SIMULATOR `127.0.0.1` IS THE HOST MAC, and on an Android emulator the host is `10.0.2.2`.
    // A phone on a desk has a route to neither, which is why every platform's default is only a
    // default and `KONEKT_URL` is read first.
    val defaultBaseUrl: String,
    val defaultRelease: String,
    val topicKey: String,
)
