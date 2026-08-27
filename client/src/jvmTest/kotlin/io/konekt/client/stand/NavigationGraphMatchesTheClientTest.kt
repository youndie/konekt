package io.konekt.client.stand

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.KonektRoutes
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.konekt.feature.shell.shared.api.NavigationResource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// TWO COPIES OF ONE CONTRACT, COMPARED — and they had drifted by three screens.
//
// The server publishes a `NavigationGraph` at `/api/v1/navigation` and its own comment says what it is
// for: *"every destination, in one place, because a deeplink that resolves on one side and not the
// other is a button that does nothing"*. The client keeps `KonektRoutes` and `B-49` exists to delete
// it in favour of the graph. Nothing held the two together, so the graph described six destinations
// while the client resolved nine: `top-up` and `esim-install` shipped with their screens and never
// joined it, and the order screen had no deeplink on either side.
//
// Taking `B-49` today would therefore have DELETED three screens from the product — silently, because
// a deeplink with no route resolves to null and a button that resolves to null does nothing at all.
//
// It is a stand test rather than a unit one because the graph is the SERVED document: `Shell.graph()`
// lives in `:server`, which a client cannot see, and comparing against a copy of it here would be a
// third copy of the same contract.
class NavigationGraphMatchesTheClientTest {
    // THE ONE DESTINATION THE GRAPH CANNOT CARRY, named rather than filtered by shape.
    //
    // `app://order/<id>` is parameterised, and the conformance kit follows every route of the graph
    // to the screen behind it with the endpoint exactly as written — so a prefix answers 404 and so
    // does a pattern, because nothing substitutes an id for a graph route. A filter on "contains a
    // placeholder" would absorb the NEXT omission silently; a named set of one fails the day it is
    // two, which is when somebody should look again.
    private val parameterised = setOf("app://order")

    private val baseUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"

    @Test
    fun `the served graph names every destination the client can resolve`() {
        val http = signedInClient()

        val served =
            runBlocking {
                Json
                    .parseToJsonElement(http.get(NavigationResource()).bodyAsText())
                    .jsonObject["routes"]
                    ?.jsonArray
                    ?.map {
                        it.jsonObject
                            .getValue("deeplink")
                            .jsonPrimitive.content
                    }?.toSet()
                    .orEmpty()
            }

        // Vacuity first: an empty answer, a renamed field or a 401 would leave this empty, and an
        // empty set compared against a non-empty one fails for the wrong reason while an empty one
        // compared against an empty one passes for no reason at all.
        assertTrue(served.size >= 4, "the graph answered $served — that is not a route table")

        assertEquals(
            KonektRoutes.map.keys - parameterised,
            served,
            "the server's graph and the client's route table disagree. `B-49` deletes the client's " +
                "copy in favour of this graph, so a destination missing here is a screen that " +
                "disappears from the product the day that happens",
        )
    }

    @Test
    fun `the graph points each destination at the address the client would use`() {
        val http = signedInClient()

        val served =
            runBlocking {
                Json
                    .parseToJsonElement(http.get(NavigationResource()).bodyAsText())
                    .jsonObject["routes"]
                    ?.jsonArray
                    ?.associate {
                        it.jsonObject
                            .getValue("deeplink")
                            .jsonPrimitive.content to
                            it.jsonObject
                                .getValue("endpoint")
                                .jsonPrimitive.content
                    }.orEmpty()
                    // THE PATTERN NORMALISED to the prefix a client resolves from. The order screen
                    // is parameterised: the graph must name an address that ANSWERS — the kit follows
                    // every route to the screen behind it — while the client's table holds the part
                    // before the placeholder, because `resolve` appends whatever follows the matched
                    // deeplink. Normalising here is what keeps that one difference from becoming a
                    // second spelling on either side.
                    .mapValues { (_, endpoint) -> endpoint.substringBefore("/{") }
            }

        // THE ADDRESSES TOO, and not only the deeplinks. A graph naming the right destinations and
        // pointing one of them somewhere else is the same button doing nothing, arrived at from the
        // other side — and both halves are derived from the same `@Resource` classes, so a
        // disagreement means one side stopped deriving.
        assertEquals(KonektRoutes.map - parameterised, served)
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
