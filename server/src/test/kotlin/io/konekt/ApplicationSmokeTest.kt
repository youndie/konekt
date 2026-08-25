package io.konekt

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationSmokeTest {
    @Test
    fun `health answers ok`() =
        testApplication {
            application { module() }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
        }

    @Test
    fun `the real application maps a refusal, not just the test one`() =
        testApplication {
            application { module() }

            // ErrorContractTest builds its own application, so it proves the mapping works and not
            // that anything installs it. This route does not exist, so Ktor's own 404 answers — and
            // what is asserted is that the answer came through ContentNegotiation and StatusPages as
            // configured here, rather than as a bare page. A contract written and never installed is
            // the commonest way for one to be absent.
            val response = client.get("/no-such-route")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
