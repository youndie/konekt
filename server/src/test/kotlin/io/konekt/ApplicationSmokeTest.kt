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
}
