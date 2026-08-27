package io.konekt.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.BuyPlan
import io.konekt.client.app.Destination
import io.konekt.client.app.KonektApp
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.observability.KonektClientObservability
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.auth.shared.api.AuthSession
import io.konekt.feature.auth.shared.api.LOGIN_CODE_DEEPLINK
import io.konekt.feature.auth.shared.api.LOGIN_DEEPLINK
import io.konekt.feature.auth.shared.api.LoginCodeScreenResource
import io.konekt.feature.auth.shared.api.LoginCodeSubmit
import io.konekt.feature.auth.shared.api.LoginForms
import io.konekt.feature.auth.shared.api.LoginScreenResource
import io.konekt.feature.auth.shared.api.LoginSubmit
import io.konekt.feature.esim.shared.api.ESIM_INSTALL_DEEPLINK
import io.konekt.feature.esim.shared.api.EsimInstallScreenResource
import io.konekt.feature.purchase.shared.api.HistoryScreenResource
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.PlansScreenResource
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.konekt.feature.purchase.shared.api.TopUpForms
import io.konekt.feature.purchase.shared.api.TopUpScreenResource
import io.konekt.feature.shell.shared.api.HOME_DEEPLINK
import io.konekt.feature.shell.shared.api.ORDERS_DEEPLINK
import io.konekt.feature.shell.shared.api.PROFILE_DEEPLINK
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.feature.shell.shared.api.SignOutAction
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

// THE APPLICATION, and it no longer knows a development route exists.
//
// It used to sign in through `/api/v1/dev/otp` — the endpoint that reads back a one-time code — and
// said so in this comment, because there was nowhere else to get one: a machine endpoint revealing
// any subscriber's code IS the authentication system, and this file did it anyway. `B-46` built the
// login screen, so the way in is now the way in.
//
// It opens on that screen, a subscriber types their number and the code they were sent, and the
// second step answers an `update_session` this runner adopts before moving home:
//
//     make stand-up && ./gradlew :client:run
fun main() {
    val baseUrl = System.getenv("KONEKT_URL") ?: "http://127.0.0.1:8080"
    val session = KonektSession()
    val http = konektHttpClient(CIO.create(), baseUrl, session, konektClientJson)

    val buy = BuyPlan(http)
    val screens =
        KonektScreenSource(
            http = http,
            realtime = SseRealtimeSource(http, konektClientJson),
            registry = konektRegistry(),
            json = konektClientJson,
            // The two login forms, and nothing else posts. A map rather than a convention, because a
            // convention that derives an address from a form id is a second route table nobody can
            // grep for.
            submits =
                mapOf(
                    LoginForms.NUMBER to addressOf<LoginSubmit>(),
                    LoginForms.CODE to addressOf<LoginCodeSubmit>(),
                    // The amount form posts to the SCREEN'S own address: the submit is a POST on
                    // the resource that serves it, so nothing here spells a second path.
                    TopUpForms.AMOUNT_FORM to addressOf<TopUpScreenResource>(),
                ),
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
                // OPENS ON THE LOGIN SCREEN. There is no session yet, and the home screen behind a
                // token would answer 401 to an application that has not asked for one.
                address = addressOf<LoginScreenResource>(),
                // A LOCAL KEY, NOT AN ADDRESS, and that is worth knowing before somebody goes looking
                // for where it is sent. `SseRealtimeSource.subscribe` ignores its topic: the path is
                // fixed and the SERVER derives the topic from the caller's token. So this string only
                // keys the overlay map — and it has to be something, because the client cannot learn
                // its own subscriber id at all. `UpdateSessionAction` does not carry one, which is why
                // the e2e stand reads it out of the database.
                topic = "konekt-session",
                // A CLIENT SETTING, and the profile screen says why it is one: which palette to
                // draw is decided where the drawing happens, so a server-driven row for it would be
                // this product's one piece of state the server holds and cannot act on.
                //
                // An environment variable rather than a control, because the application has no
                // settings screen and inventing one to test a palette is a feature. `KONEKT_DARK=true`.
                darkMode = System.getenv("KONEKT_DARK") == "true",
                // THE ONE TRANSITION THIS BUILD HAS. The home screen's banner offers "See plans" and
                // the deeplink is spelled once, in the shared module both sides read.
                routes =
                    mapOf(
                        PLANS_DEEPLINK to plansAddress(),
                        // The install flow, which had routes and no address until B-54.
                        ESIM_INSTALL_DEEPLINK to addressOf<EsimInstallScreenResource>(),
                        // Matched by PREFIX for its result: `app://top-up/<id>` carries the id
                        // after the deeplink, exactly as the login step carries a number.
                        TOP_UP_DEEPLINK to addressOf<TopUpScreenResource>(),
                        // THE FOUR TABS. Written here for now, and this map is what `B-49` exists to
                        // delete: the server serves the same pairing as a `NavigationGraph` at
                        // `/api/v1/navigation`, and a client resolving deeplinks from a map it wrote
                        // by hand is a second copy of a contract the server already publishes. Four
                        // entries is the point at which that stops being a detail.
                        HOME_DEEPLINK to homeAddress(),
                        ORDERS_DEEPLINK to ordersAddress(),
                        PROFILE_DEEPLINK to profileAddress(),
                        // The login step, matched by PREFIX: the server puts the number in the query
                        // and a map keyed on the whole string would need an entry per subscriber.
                        LOGIN_CODE_DEEPLINK to addressOf<LoginCodeScreenResource>(),
                        LOGIN_DEEPLINK to addressOf<LoginScreenResource>(),
                    ),
                // Announced rather than swallowed: a handler that silently did nothing would make a
                // button that does nothing indistinguishable from one whose handler is missing.
                onAction = { action ->
                    when {
                        // THE SESSION LANDS HERE, which is the one thing a holder must not do: it
                        // would be a screen holder that knows what a token is. The runner adopts it
                        // and answers with the home address, so signing in ends the same way every
                        // other transition does.
                        action is UpdateSessionAction -> {
                            session.adopt(SessionTokens(action.accessToken, action.refreshToken))
                            // START OVER, and the login screen is the reason. It is where this
                            // application opens, so it is the bottom of the stack — replacing the top
                            // left it underneath the home screen, which put a back control on a tab
                            // and pointed it at a code already spent.
                            Destination.startOver(homeAddress())
                        }

                        // LEAVING. The order is the whole of it: tell the server FIRST, while the
                        // token is still usable, then drop the tokens. The other way round the
                        // request goes out unauthenticated and the refresh family lives on — a
                        // subscriber who signed out on a shared machine and a session that did not
                        // end. The server's answer is not waited on for correctness, only for order:
                        // if the call fails the tokens still go, because the one thing that must not
                        // happen is a screen saying "signed out" with a live session behind it.
                        action is SignOutAction -> {
                            suspendRunCatching { http.post(AuthSession.Logout()) }
                            session.clear()
                            // The same boundary from the other side, and worse if it is missed: back
                            // from here would return to a screen whose token this line just dropped.
                            Destination.startOver(addressOf<LoginScreenResource>())
                        }

                        // BUYING, for the same reason: a holder with an opinion about purchases is
                        // this application's holder rather than a reusable one.
                        else -> {
                            buy.addressFor(action)?.let(Destination::next) ?: run {
                                println("konekt: no handler for $action")
                                null
                            }
                        }
                    }
                },
            )
        }
    }
}

// The address, derived from the `@Resource` rather than typed again. The three lines are a copy of
// `io.konekt.openapi.ResourceAddresses`; the ADDRESS is not — it is still spelled once, in the
// annotation. The server's copy lives in `:server`, which a client cannot see.
private fun homeAddress(): String = addressOf<HomeScreenResource>()

private fun plansAddress(): String = addressOf<PlansScreenResource>()

private fun ordersAddress(): String = addressOf<HistoryScreenResource>()

private fun profileAddress(): String = addressOf<ProfileScreenResource>()

// Derived from the `@Resource` rather than typed again. The ADDRESS is still spelled once, in the
// annotation; this is a copy of the three lines in `io.konekt.openapi.ResourceAddresses`, which lives
// in `:server` and a client cannot see.
private inline fun <reified T : Any> addressOf(): String =
    ResourcesFormat()
        .encodeToPathPattern(serializer<T>())
        .let { if (it.startsWith("/")) it else "/$it" }
