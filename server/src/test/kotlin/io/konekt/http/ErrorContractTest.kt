package io.konekt.http

import io.konekt.domain.ApiError
import io.konekt.domain.Currency
import io.konekt.domain.KonektException
import io.konekt.domain.Money
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
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
}
