package io.konekt.client.stand

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.client.app.KonektRoutes
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.app.resolve
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.CreateTopUpRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.purchase.shared.api.TopUps
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A SCREEN NOTHING LEADS TO IS A SCREEN NOBODY CAN OPEN, and this build shipped three of them.
//
// The confirmation, the top-up and the eSIM wizard each had a working server half — routes installed,
// use cases bound, saga running — and no served tree carrying an action that led there. Every guard
// this repository has was green for all three, correctly, because each answers a different question:
// `FeatureModulesReachTheGraphTest` asks whether the module is in the composition root, and it was;
// `RoutesResolveWhatTheyInjectTest` asks whether the injections are bound, and they were;
// `TckCoverageTest` asks whether a blind walk visits every endpoint it can, and it did.
//
// What none of them asks is whether any tree the server EMITS points at a given screen. That is a
// property of the data rather than of the object graph, which is why it needs a check of its own and
// why that check has to fetch real screens from a real deployment (`B-56`).
//
// ITS SUBJECT IS THE SERVED GRAPH, which is what a client resolves a deeplink through since `B-49`.
// A destination the graph names and nothing points at is a screen nobody can open; a deeplink a tree
// carries and the graph does not name is a button that does nothing. Both are the same defect from
// opposite ends, and both are visible from here because both halves now come from the server.
class EveryScreenIsReachableTest {
    // The wire name, read off the serializer rather than typed. `"navigate"` written here would be a
    // second spelling of a name that lives in the toolkit's `@SerialName`, and a rename upstream
    // would make this guard find nothing while staying green.
    private val navigateWireName: String = NavigateAction.serializer().descriptor.serialName

    private val baseUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"

    // WHAT NOTHING POINTS AT, DELIBERATELY — and the list is asserted as a SET EQUALITY rather than
    // as a floor, so a screen that becomes reachable and stays here fails. An exemption list checked
    // with `containsAll` is how a guard like this quietly stops guarding anything.
    // Fetched once and shared by both assertions: it is what the client would resolve with.
    // WHAT THE CLIENT WOULD RESOLVE WITH: the served graph merged over the bootstrap, which is
    // exactly what `KonektApp` holds. Asserting against the graph alone would call the order screen
    // unreachable — it is the one destination the graph cannot carry (see `KonektRoutes`, U15) — and
    // asserting against the bootstrap alone would be the copy `B-49` deleted.
    private fun routeTable(http: HttpClient): Map<String, String> =
        KonektRoutes.bootstrap +
            runBlocking {
                requireNotNull(navigationOf(http)) { "the deployment served no graph" }
            }

    private fun navigationOf(http: HttpClient): Map<String, String>? =
        runBlocking {
            KonektScreenSource(
                http = http,
                realtime = SseRealtimeSource(http, konektClientJson),
                registry = konektRegistry(),
                json = konektClientJson,
            ).navigation()
        }

    private val reachedByNothing =
        mapOf(
            // The way in. Nothing can navigate to it because it is where the application OPENS —
            // `KonektRoutes.loginAddress` — and because signing out is an action the runner answers
            // with rather than a `navigate` on a screen.
            "app://login" to "the application opens here; arriving is not navigating",
            // Step two. Reached by the login submit's ANSWER — a `navigate` the endpoint returns, not
            // one that sits in a tree — so a walk over trees cannot see it. Posting a number to find
            // out would make this guard perform the product's own side effects.
            "app://login/code" to "reached by an endpoint's answer rather than by a control on a screen",
        )

    @Test
    fun `every screen the client knows an address for is the destination of something served`() {
        val http = signedInClient()

        val routeTable = routeTable(http)
        val reachable =
            runBlocking {
                // Buying and topping up FIRST, so the screens that only exist once something has
                // happened exist. Without this the purchase result is absent rather than unreachable,
                // and the guard would pass by not looking.
                val orderScreen = seed(http)
                // WHAT IS WALKED is the client's route table PLUS the screens the runner arrives at
                // by answering an action rather than by resolving a deeplink. The purchase result is
                // the only one, and leaving it out is how the eSIM install door — which lives on it
                // and nowhere else — was reported unreachable by a guard looking in the wrong places.
                // ONE ENTRY IS A PREFIX AND NOT AN ADDRESS. `app://order` resolves to
                // `/api/v1/screens/orders`, which the server does not serve — only
                // `/api/v1/screens/orders/{orderId}` is a route — so fetching it answers 404 with an
                // empty body and the decode below fails on nothing. The seeded order screen below IS
                // that screen, with an id, so the walk loses no coverage by skipping the bare prefix.
                val addresses =
                    routeTable.values
                        .distinct()
                        .filterNot { orderScreen.startsWith("$it/") } + orderScreen
                deeplinksFoundIn(http, addresses)
            }.mapNotNull { routeFor(it, routeTable) }.toSet()

        // Vacuity first, and it is not ceremony: a run that fetched nothing — a stand answering 401,
        // a decode that returned no actions — would make every screen "unreachable" and the assertion
        // below would fail for the wrong reason, or, with the exemption list grown to match, pass
        // while proving nothing.
        assertTrue(
            reachable.size >= 4,
            "only ${reachable.size} destinations were found across every served screen; the walk did not run",
        )

        val unreached = routeTable.keys - reachable

        assertEquals(
            reachedByNothing.keys,
            unreached,
            buildString {
                appendLine("the set of screens nothing leads to is not the declared one.")
                (unreached - reachedByNothing.keys).forEach {
                    appendLine("  reachable from nowhere and not declared: $it")
                }
                (reachedByNothing.keys - unreached).forEach {
                    appendLine("  declared unreachable and now reached: $it — delete the entry")
                }
                appendLine("declared:")
                reachedByNothing.forEach { (deeplink, why) -> appendLine("  $deeplink — $why") }
            },
        )
    }

    // WHICH ROUTE A DEEPLINK WENT THROUGH, not which address it produced — and the difference is what
    // this guard got wrong until an order screen was addressable at all.
    //
    // A prefix route is reached by every deeplink UNDER it: `app://order/8f21` reaches `app://order`,
    // and comparing resolved ADDRESSES would call the route unreached because no tree carries the bare
    // prefix. The plans detail hid the same mistake by accident — its route is also a tab, so the bar
    // pointed at it whatever the cards did.
    private fun routeFor(
        deeplink: String,
        routeTable: Map<String, String>,
    ): String? {
        if (deeplink in routeTable) return deeplink
        return routeTable.keys
            .filter { deeplink.startsWith("$it?") || deeplink.startsWith("$it/") }
            .maxByOrNull { it.length }
    }

    // THE OTHER DIRECTION, and it replaces a test that no longer has a subject.
    //
    // `NavigationGraphMatchesTheClientTest` held the served graph against the client's copy of it, and
    // `B-49` deleted the copy: a deeplink resolves through the graph now, so there is nothing to
    // disagree with. What that test was actually worth is this — a `navigate` the server EMITS that
    // the client cannot resolve is a button that does nothing, and it is now the only way that
    // failure can happen.
    //
    // It caught the graph three destinations short once already; with one table left it would have
    // been three dead buttons instead.
    @Test
    fun `every deeplink the server sends resolves to somewhere`() {
        val http = signedInClient()
        val routeTable = routeTable(http)

        val emitted =
            runBlocking {
                val orderScreen = seed(http)
                deeplinksFoundIn(
                    http,
                    routeTable.values.distinct().filterNot { orderScreen.startsWith("$it/") } + orderScreen,
                )
            }

        assertTrue(emitted.size >= 4, "only ${emitted.size} deeplinks were found; the walk did not run")

        assertEquals(
            emptyList(),
            emitted.filter { routeFor(it, routeTable) == null }.sorted(),
            "the server sends these and the client resolves none of them — every one is a control " +
                "that looks pressable and does nothing",
        )
    }

    // THE POSITIVE CONTROL. Without it the assertion above passes on a walk that found every address
    // by accident — for instance if `resolve` answered the address it was given. Removing one screen
    // from the client's table must make it disappear from the reachable set, not survive it.
    @Test
    fun `a screen the client cannot resolve is not counted as reached`() {
        val withoutPlans = routeTable(signedInClient()) - "app://plans"

        assertEquals(
            null,
            resolve("app://plans", withoutPlans),
            "a deeplink with no entry resolved anyway — every count in the test above would be a fiction",
        )
        // And the other half, which is what the assertion above is actually made of now: a deeplink
        // under a prefix reaches the ROUTE, not an address of its own.
        assertEquals("app://plans", routeFor("app://plans/tr-10gb-30d", withoutPlans + ("app://plans" to "/x")))
        assertEquals(null, routeFor("app://nothing-like-this/1", withoutPlans))
    }

    private suspend fun deeplinksFoundIn(
        http: HttpClient,
        addresses: List<String>,
    ): Set<String> =
        buildSet {
            addresses.forEach { address ->
                addAll(navigations(Json.parseToJsonElement(http.get(address).bodyAsText())))
            }
        }

    // EVERY `navigate` ANYWHERE IN THE BODY, read out of the JSON rather than out of decoded objects.
    //
    // The first version of this walked the decoded tree by reflection, following members that were a
    // `KompotComponent` or a list of them — and it reported the home and profile screens as reachable
    // from nowhere. Both are on the bottom bar. `BottomNavComponent` holds `items`, and a
    // `BottomNavItem` is neither a component nor an action: it is a data holder CARRYING one, so the
    // walk stepped over all four tabs and the comment above it claimed otherwise.
    //
    // Reading the JSON has no such blind spot and needs no list of shapes to keep in step with the
    // dictionary — an action is an object with `"type": "navigate"` wherever it sits, which is the
    // toolkit's own contract rather than an assumption about konekt's components. What it does NOT
    // prove is that the client can decode what it found; `ClientDecodesEveryActionTest` is that, and
    // conflating the two would leave both weaker.
    private fun navigations(node: JsonElement): Set<String> =
        buildSet {
            // Two `if`s rather than a `when`, and that is the formatter and the compiler disagreeing
            // rather than a preference: a `when` needs an `else` for the primitive case, ktlint wants
            // it braced because the other branches are multiline, and a braced `Unit` is then an
            // unused expression that `-Werror` refuses. A primitive has nothing to walk into anyway.
            if (node is JsonObject) {
                if (node["type"]?.jsonPrimitive?.contentOrNull == navigateWireName) {
                    node["deeplink"]?.jsonPrimitive?.contentOrNull?.let(::add)
                }
                node.values.forEach { addAll(navigations(it)) }
            }
            if (node is JsonArray) {
                node.forEach { addAll(navigations(it)) }
            }
        }

    // Money in, a plan bought and confirmed — so that the screens which only exist after something
    // happened are there to be pointed at.
    private suspend fun seed(http: HttpClient): String {
        http.post(TopUps()) { setBody(CreateTopUpRequest(amountMinor = 5_000)) }
        val order: PurchaseOrderResponse =
            http
                .post(
                    Purchases(),
                ) { setBody(CreatePurchaseRequest("home-20gb-30d")) }
                .body()
        http.post(Purchases.ById.Confirm(parent = Purchases.ById(orderId = order.orderId)))
        return "/api/v1/screens/orders/${order.orderId}"
    }

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
}
