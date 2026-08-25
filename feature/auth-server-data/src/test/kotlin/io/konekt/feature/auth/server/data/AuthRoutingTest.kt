package io.konekt.feature.auth.server.data

import io.github.youndie.kompot.auth.kompotAuthSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.konekt.domain.ApiError
import io.konekt.http.configureStatusPages
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.SystemClock
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.plus
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// The whole way in, through a real route, a real database and a real JWT.
//
// The domain tests already cover the rules against MockK. What this adds is everything between the
// use case and the wire — the resource paths, the JSON, the Koin graph, and the one call that is easy
// to get wrong and impossible to notice: respondKompotAction.
class AuthRoutingTest {
    private val jwt = JwtConfig(secret = "test-secret", issuer = "konekt-test", audience = "konekt-app")

    private val clock: KonektClock = SystemClock

    private fun Application.testModule() {
        val database = PostgresHarness.database
        install(Koin) {
            modules(
                module { single { clock } },
                // The auth serializers module is not optional here, and forgetting it is exactly how
                // this test first failed: encoding UpdateSessionAction polymorphically without it
                // throws "Serializer for subclass 'UpdateSessionAction' is not found", which arrives
                // as a 500 with no clue in it unless something is bound to slf4j. Hence the logback
                // test dependency beside it.
                module {
                    single {
                        Json {
                            ignoreUnknownKeys = true
                            classDiscriminator = "type"
                            serializersModule = kompotCoreSerializersModule + kompotAuthSerializersModule
                        }
                    }
                },
                authModule(database, jwt, revealCodes = true),
            )
        }
        install(ContentNegotiation) { json() }
        install(Resources)
        configureStatusPages()
        routing {
            authRoutes()
            devOtpRoutes(
                org.koin.core.context.GlobalContext
                    .get()
                    .get(),
            )
        }
    }

    @BeforeTest
    fun clean() {
        PostgresHarness.truncateAll()
    }

    @Test
    fun `a subscriber signs in with the code they were sent`() =
        testApplication {
            application { testModule() }

            client.requestOtp("15550109999")
            val code = client.devCode("15550109999")

            val response = client.verifyOtp("15550109999", code)

            assertEquals(HttpStatusCode.OK, response.status)
            // Through respondKompotAction, so the root carries its "type" discriminator. A plain
            // call.respond drops it — nested children serialise perfectly, which is what makes the
            // mistake invisible — and the client then receives an unknown action and does nothing,
            // with a 200.
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("update_session", body["type"]?.jsonPrimitive?.content)
            assertTrue(body["accessToken"]?.jsonPrimitive?.content?.isNotBlank() == true)
            assertNotEquals(
                body["accessToken"]?.jsonPrimitive?.content,
                body["refreshToken"]?.jsonPrimitive?.content,
                "the two tokens are the same string, so the type claim is not doing its job",
            )
        }

    @Test
    fun `a code works once`() =
        testApplication {
            application { testModule() }

            client.requestOtp("15550109999")
            val code = client.devCode("15550109999")

            assertEquals(HttpStatusCode.OK, client.verifyOtp("15550109999", code).status)
            // The second attempt meets no challenge at all, and is answered exactly like a wrong
            // code — which is also what a number nobody has asked about is answered.
            assertEquals(HttpStatusCode.UnprocessableEntity, client.verifyOtp("15550109999", code).status)
        }

    @Test
    fun `six wrong codes lock the number and the answer says for how long`() =
        testApplication {
            application { testModule() }

            client.requestOtp("15550109999")

            repeat(5) {
                assertEquals(HttpStatusCode.UnprocessableEntity, client.verifyOtp("15550109999", "000000").status)
            }

            val locked = client.verifyOtp("15550109999", "000000")

            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            // A client told to slow down with no number picks one, and the number it picks is
            // "immediately".
            assertEquals("900", locked.headers[HttpHeaders.RetryAfter])
            assertEquals("rate_limited", locked.error().code)
        }

    @Test
    fun `an unknown number is answered exactly like a known one`() =
        testApplication {
            application { testModule() }

            // Sign one number up so the two really are in different states.
            client.requestOtp("15550109999")
            client.verifyOtp("15550109999", client.devCode("15550109999"))

            val known = client.requestOtp("15550109999")
            val unknown = client.requestOtp("15550100000")

            assertEquals(known.status, unknown.status)
            assertEquals(known.bodyAsText(), unknown.bodyAsText())
        }

    private suspend fun io.ktor.client.HttpClient.requestOtp(msisdn: String): HttpResponse =
        post("/api/v1/auth/otp/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"msisdn":"$msisdn"}""")
        }

    private suspend fun io.ktor.client.HttpClient.verifyOtp(
        msisdn: String,
        code: String,
    ): HttpResponse =
        post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"msisdn":"$msisdn","code":"$code"}""")
        }

    private suspend fun io.ktor.client.HttpClient.devCode(msisdn: String): String {
        val body = get("/api/v1/dev/otp?msisdn=$msisdn").bodyAsText()
        return Json
            .parseToJsonElement(body)
            .jsonObject["code"]!!
            .jsonPrimitive.content
    }

    private suspend fun HttpResponse.error(): ApiError = Json.decodeFromString(ApiError.serializer(), bodyAsText())
}
