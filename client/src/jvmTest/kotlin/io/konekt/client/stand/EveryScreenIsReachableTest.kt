package io.konekt.client.stand

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.client.app.KonektRoutes
import io.konekt.client.app.resolve
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
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
// IT READS THE CLIENT'S OWN ROUTE TABLE rather than a copy: `KonektRoutes` is what both runners are
// wired with, so a deeplink the server sends and the client cannot resolve fails here too — which is
// the same bug seen from the other end.
class EveryScreenIsReachableTest {
    // The wire name, read off the serializer rather than typed. `"navigate"` written here would be a
    // second spelling of a name that lives in the toolkit's `@SerialName`, and a rename upstream
    // would make this guard find nothing while staying green.
    private val navigateWireName: String = NavigateAction.serializer().descriptor.serialName

    private val baseUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"

    // WHAT NOTHING POINTS AT, DELIBERATELY — and the list is asserted as a SET EQUALITY rather than
    // as a floor, so a screen that becomes reachable and stays here fails. An exemption list checked
    // with `containsAll` is how a guard like this quietly stops guarding anything.
    private val reachedByNothing =
        mapOf(
            // The way in. Nothing can navigate to it because it is where the application OPENS —
            // `KonektRoutes.loginAddress` — and because signing out is an action the runner answers
            // with rather than a `navigate` on a screen.
            KonektRoutes.map.getValue("app://login") to
                "the application opens here; arriving is not navigating",
            // Step two. Reached by the login submit's ANSWER — a `navigate` the endpoint returns, not
            // one that sits in a tree — so a walk over trees cannot see it. Posting a number to find
            // out would make this guard perform the product's own side effects.
            KonektRoutes.map.getValue("app://login/code") to
                "reached by an endpoint's answer rather than by a control on a screen",
        )

    @Test
    fun `every screen the client knows an address for is the destination of something served`() {
        val http = signedInClient()

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
                deeplinksFoundIn(http, KonektRoutes.map.values.distinct() + orderScreen)
            }.mapNotNull { resolve(it, KonektRoutes.map) }.toSet()

        // Vacuity first, and it is not ceremony: a run that fetched nothing — a stand answering 401,
        // a decode that returned no actions — would make every screen "unreachable" and the assertion
        // below would fail for the wrong reason, or, with the exemption list grown to match, pass
        // while proving nothing.
        assertTrue(
            reachable.size >= 4,
            "only ${reachable.size} destinations were found across every served screen; the walk did not run",
        )

        val unreached = KonektRoutes.map.values.toSet() - reachable

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
                reachedByNothing.forEach { (address, why) -> appendLine("  $address — $why") }
            },
        )
    }

    // THE POSITIVE CONTROL. Without it the assertion above passes on a walk that found every address
    // by accident — for instance if `resolve` answered the address it was given. Removing one screen
    // from the client's table must make it disappear from the reachable set, not survive it.
    @Test
    fun `a screen the client cannot resolve is not counted as reached`() {
        val withoutPlans = KonektRoutes.map - "app://plans"

        assertEquals(
            null,
            resolve("app://plans", withoutPlans),
            "a deeplink with no entry resolved anyway — every count in the test above would be a fiction",
        )
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
