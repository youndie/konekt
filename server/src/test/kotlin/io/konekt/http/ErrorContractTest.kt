package io.konekt.http

import io.konekt.domain.ApiError
import io.konekt.domain.Currency
import io.konekt.domain.KonektException
import io.konekt.domain.Money
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Every refusal this server can produce, driven through a real route and a real response.
//
// Per exception type, not in aggregate: a single "an error becomes a status" test passes on a
// mapping that answers 500 for five of the six.
class ErrorContractTest {
    // The table under test. It is also the completeness check — `everyRefusalIsCovered` asserts it
    // names every subclass of the sealed hierarchy, so a case added to the domain and not thought
    // about here fails a test as well as the compiler.
    private val cases: List<Pair<KonektException, HttpStatusCode>> =
        listOf(
            KonektException.NotFound("order") to HttpStatusCode.NotFound,
            KonektException.Validation("msisdn", "not a phone number") to HttpStatusCode.UnprocessableEntity,
            KonektException.Conflict("the plan is no longer on sale") to HttpStatusCode.Conflict,
            KonektException.InsufficientFunds(Money.ofMajor(12, Currency.DEFAULT)) to HttpStatusCode.Conflict,
            KonektException.Unauthorized() to HttpStatusCode.Unauthorized,
            KonektException.RateLimited(retryAfterSeconds = 60) to HttpStatusCode.TooManyRequests,
        )

    // An Application extension rather than a lambda inside `application { }`: there, `install`
    // resolves against the test builder as well and the call is ambiguous.
    private fun Application.probeModule(failure: Throwable) {
        install(ContentNegotiation) { json() }
        configureStatusPages()
        routing { get("/probe") { throw failure } }
    }

    private fun probe(
        failure: Throwable,
        assertions: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) = testApplication {
        application { probeModule(failure) }

        assertions(client.get("/probe"))
    }

    @Test
    fun `every refusal answers its own status and carries its code`() {
        cases.forEach { (failure, expected) ->
            probe(failure) { response ->
                assertEquals(expected, response.status, "${failure.code} answered ${response.status}")

                val body = Json.decodeFromString(ApiError.serializer(), response.bodyAsText())
                assertEquals(failure.code, body.code)
                assertTrue(body.message.isNotBlank(), "${failure.code} answered with an empty message")
            }
        }
    }

    @Test
    fun `every refusal in the domain is covered here`() {
        // The compiler already refuses an unmapped case in `httpStatus`, because the `when` has no
        // `else`. This is the other half: a case that IS mapped but that nobody thought to exercise.
        val declared = KonektException::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        val exercised = cases.map { it.first::class.simpleName }.toSet()

        assertEquals(declared, exercised, "a refusal exists in the domain and is not driven through a route here")
        assertTrue(declared.size >= 6, "found ${declared.size} refusals — is reflection seeing the hierarchy?")
    }

    @Test
    fun `being told to slow down comes with a number`() {
        probe(KonektException.RateLimited(retryAfterSeconds = 45)) { response ->
            assertEquals("45", response.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `an unexpected failure says nothing about itself`() {
        // The message of an unexpected exception is written for whoever wrote the code — it carries
        // table names, identifiers, sometimes a query — and a subscriber is not that reader.
        probe(IllegalStateException("connection to postgres-svc:5432 refused for user konekt")) { response ->
            assertEquals(HttpStatusCode.InternalServerError, response.status)

            val text = response.bodyAsText()
            assertTrue("postgres" !in text, "the internal error leaked its cause: $text")
            assertTrue("konekt" !in text, "the internal error leaked its cause: $text")
            assertEquals("internal_error", Json.decodeFromString(ApiError.serializer(), text).code)
        }
    }

    @Test
    fun `a client that went away is not an internal error`() {
        // WHAT THIS IS FOR is not the status code — nobody is left to read one — it is that the
        // failure does not reach the branch that logs at ERROR and reports a crash.
        //
        // The realtime stream ends this way every single time: the subscriber closes the application,
        // locks the phone, loses signal. Ktor's CIO wraps the broken pipe in a ChannelWriteException,
        // and until this branch existed that became a katcher group — the most ordinary event the
        // product has, filling the place where the reports that mean something have to be visible.
        //
        // Found by closing the desktop client against a deployed instance and watching the log.
        probe(ChannelWriteException("Cannot write to channel", IOException("Broken pipe"))) { response ->
            // Not 500: no handler responded at all, so the test client sees the empty answer the
            // server gave rather than an internal-error body it would have had to build a channel
            // for. What is asserted is the ABSENCE of the internal-error contract.
            assertTrue(
                "internal_error" !in response.bodyAsText(),
                "a departed client was answered as an internal failure, so it was also reported as one",
            )
        }
    }

    @Test
    fun `a body this endpoint cannot read is the caller's fault, not ours`() =
        testApplication {
            // A REAL receive, not a thrown BadRequestException. Throwing one directly would prove the
            // handler catches that class and say nothing about whether `call.receive` produces it —
            // and that second half is the whole finding: every route that receives a body answered
            // 500 for a malformed one until the conformance walk sent a shape of its own (B-24).
            application {
                install(ContentNegotiation) { json() }
                configureStatusPages()
                routing {
                    post("/probe") {
                        call.receive<VerifyProbe>()
                        call.respondText("unreachable")
                    }
                }
            }

            val response =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"formId":"login","values":{"msisdn":"1555","code":"000000"}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)

            val text = response.bodyAsText()
            assertEquals("bad_request", Json.decodeFromString(ApiError.serializer(), text).code)
            // The cause names the Kotlin class it failed to build. Which internal type backs an
            // endpoint is not the caller's business, and it is the kind of thing that goes into a
            // client's error log and stays there.
            assertTrue("VerifyProbe" !in text, "the refusal named the type it could not build: $text")
        }

    @Serializable
    private data class VerifyProbe(
        val msisdn: String,
        val code: String,
    )
}
