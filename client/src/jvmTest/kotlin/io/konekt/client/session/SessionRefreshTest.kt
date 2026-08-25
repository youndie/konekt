package io.konekt.client.session

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.encodeKompotAction
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// "And the client stores it" — through the real client, with the server replaced rather than the
// logic.
//
// What is actually under test is the seam between kompot's `update_session` action and ktor's bearer
// plugin: the toolkit hands over a session inside an ordinary action response, and the plugin knows
// how to spend one but not where to keep it. Everything here is about that handover.
//
// `runBlocking` and not `runTest`, for the reason the saga tests carry: the plugin and the engine run
// coroutines this test did not start, and `runTest` waits a minute for them and then fails with
// "the test body did not run to completion" — which names the test rather than the cause.
class SessionRefreshTest {
    private val json = konektClientJson

    // Encoded the way the SERVER encodes it — polymorphically, with a "type" at the root. Writing
    // the JSON by hand here would test a shape nobody sends.
    private fun sessionAction(
        access: String,
        refresh: String,
    ) = json.encodeKompotAction(UpdateSessionAction(access, refresh))

    @Test
    fun `a stored session is attached to the very first request`() =
        runBlocking {
            val authorizations = mutableListOf<String?>()
            val session = KonektSession(InMemorySessionStore(SessionTokens("access-1", "refresh-1")))

            val engine =
                MockEngine { request ->
                    authorizations += request.headers[HttpHeaders.Authorization]
                    if (request.headers[HttpHeaders.Authorization] == null) {
                        respondError(HttpStatusCode.Unauthorized)
                    } else {
                        respond("ok", HttpStatusCode.OK)
                    }
                }

            val client = konektHttpClient(engine, "http://konekt.test", session, json)
            assertEquals("ok", client.get("/anything").bodyAsText())

            // ONE request, with the header on it. Worth asserting because the opposite is widely
            // believed and was believed here: ktor's bearer provider is often described as sending
            // nothing until it has seen a 401, and with tokens already in the store it does not wait.
            // The bare-first-request shape belongs to a session that has NO tokens yet, which is a
            // different case and not this one.
            assertEquals(listOf<String?>("Bearer access-1"), authorizations)
        }

    @Test
    fun `a 401 spends the refresh token and the request is retried with the new one`() =
        runBlocking {
            val session = KonektSession(InMemorySessionStore(SessionTokens("stale", "refresh-1")))
            val seen = mutableListOf<String>()

            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    val authorization = request.headers[HttpHeaders.Authorization]
                    seen += "$path ${authorization ?: "-"}"

                    when {
                        path.endsWith("/refresh") -> {
                            respond(
                                sessionAction("fresh", "refresh-2"),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        authorization == "Bearer fresh" -> {
                            respond("ok", HttpStatusCode.OK)
                        }

                        else -> {
                            respondError(HttpStatusCode.Unauthorized)
                        }
                    }
                }

            val client = konektHttpClient(engine, "http://konekt.test", session, json)
            assertEquals("ok", client.get("/anything").bodyAsText())

            // The new pair is kept, not just used once.
            assertEquals(SessionTokens("fresh", "refresh-2"), session.tokens.value)
            // The refresh goes out WITHOUT the token that just failed, which is what
            // markAsRefreshTokenRequest() buys. Before it was added this line read `Bearer stale`.
            assertTrue(seen.any { it.endsWith("/api/v1/auth/session/refresh -") }, seen.toString())
        }

    @Test
    fun `a refresh the server refuses ends the session instead of being retried`() =
        runBlocking {
            val session = KonektSession(InMemorySessionStore(SessionTokens("stale", "refresh-1")))
            var refreshAttempts = 0

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/refresh")) {
                        refreshAttempts += 1
                        respondError(HttpStatusCode.Unauthorized)
                    } else {
                        respondError(HttpStatusCode.Unauthorized)
                    }
                }

            val client = konektHttpClient(engine, "http://konekt.test", session, json)
            client.get("/anything")

            // ONCE. The server rotates refresh tokens and detects reuse, so a second attempt with the
            // same token ends the family — retrying is the one response guaranteed to make it worse.
            assertEquals(1, refreshAttempts)
            assertNull(session.tokens.value, "a refused refresh left the tokens in place")
        }

    @Test
    fun `applying the session action is what signs the client in`() =
        runBlocking {
            val session = KonektSession()
            assertNull(session.tokens.value)

            // Exactly what arrives from `verify`: a polymorphic action, decoded through the
            // application's Json rather than read as a pair of strings.
            val action = json.decodeKompotAction(sessionAction("access-1", "refresh-1"))
            session.apply(action as UpdateSessionAction)

            assertEquals(SessionTokens("access-1", "refresh-1"), session.tokens.value)
        }
}
