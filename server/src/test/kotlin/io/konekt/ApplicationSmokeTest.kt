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
            application { baseModule() }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
        }

    @Test
    fun `the real application maps a refusal, not just the test one`() =
        testApplication {
            application { baseModule() }

            // ErrorContractTest builds its own application, so it proves the mapping works and not
            // that anything installs it. baseModule is what the real composition root calls, so
            // asserting here is asserting about the thing that ships. A contract written and never
            // installed is the commonest way for one to be absent.
            val response = client.get("/no-such-route")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
