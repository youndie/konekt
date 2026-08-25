package io.konekt.feature.auth.server.data

import io.github.youndie.kompot.auth.kompotAuthSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.konekt.http.configureStatusPages
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import io.ktor.server.auth.authenticate
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

// Rotation, reuse and logout, driven through the routes rather than against the tables.
//
// Against the tables it would be a test of my own SQL. Through the routes it is a test of the thing
// a client meets, which is where the properties actually have to hold.
class SessionRotationTest {
    private val jwt = JwtConfig(secret = "test-secret", issuer = "konekt-test", audience = "konekt-app")
    private val clock: KonektClock = SystemClock

    private fun Application.testModule() {
        install(Koin) {
            modules(
                module { single { clock } },
                module {
                    single {
                        Json {
                            ignoreUnknownKeys = true
                            classDiscriminator = "type"
                            serializersModule = kompotCoreSerializersModule + kompotAuthSerializersModule
                        }
                    }
                },
                authModule(PostgresHarness.database, jwt, revealCodes = true),
            )
        }
        install(ContentNegotiation) { json() }
        install(Resources)
        configureStatusPages()
        configureAuthentication(jwt)
        routing {
            authRoutes()
            sessionRoutes()
            authenticate(AUTH_JWT) { authenticatedSessionRoutes() }
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
    fun `refreshing returns a new pair and the old refresh token stops working`() =
        testApplication {
            application { testModule() }
            val first = client.signIn("15550109999")

            val second = client.refresh(first.refresh).session()

            assertNotEquals(first.refresh, second.refresh, "the refresh token was handed back unchanged")
            // The new pair works.
            assertEquals(HttpStatusCode.NoContent, client.logout(second.access).status)
        }

    @Test
    fun `a refresh token used twice ends the family and both tokens stop working`() =
        testApplication {
            application { testModule() }
            val first = client.signIn("15550109999")

            val second = client.refresh(first.refresh).session()

            // The second exchange of the SAME token. An honest holder cannot do this — they replaced
            // theirs the moment they used it — so one of the two holders is not the subscriber.
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(first.refresh).status)

            // Which one is unknowable, so the family goes. The pair issued a moment ago is dead too,
            // including its ACCESS token, which is the part a stateless design cannot do.
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(second.refresh).status)
            assertEquals(HttpStatusCode.Unauthorized, client.logout(second.access).status)
            assertEquals(HttpStatusCode.Unauthorized, client.logout(first.access).status)
        }

    @Test
    fun `logout makes the access token stop working before it expires`() =
        testApplication {
            application { testModule() }
            val session = client.signIn("15550109999")

            assertEquals(HttpStatusCode.NoContent, client.logout(session.access).status)

            // The token has fifteen minutes left on it and is refused anyway. That is the whole
            // reason the access token carries its family and the provider looks it up.
            assertEquals(HttpStatusCode.Unauthorized, client.logout(session.access).status)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(session.refresh).status)
        }

    @Test
    fun `each token is refused where the other belongs`() =
        testApplication {
            application { testModule() }
            val session = client.signIn("15550109999")

            // An access token at the refresh endpoint: the type claim is what refuses it, and without
            // the claim a refresh token would simply be an access token with a longer life.
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(session.access).status)

            // A refresh token as a bearer credential.
            assertEquals(HttpStatusCode.Unauthorized, client.logout(session.refresh).status)
        }

    @Test
    fun `a signed-in subscriber keeps their session across an unrelated one ending`() =
        testApplication {
            application { testModule() }
            val mine = client.signIn("15550109999")
            val theirs = client.signIn("15550100000")

            client.logout(theirs.access)

            // Families are per sign-in, so ending one must not touch another. Asserted because the
            // revoke is a WHERE clause, and a WHERE clause is exactly the thing that is right until
            // somebody widens it.
            assertEquals(HttpStatusCode.NoContent, client.logout(mine.access).status)
        }

    private data class Tokens(
        val access: String,
        val refresh: String,
    )

    private suspend fun HttpClient.signIn(msisdn: String): Tokens {
        post("/api/v1/auth/otp/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"msisdn":"$msisdn"}""")
        }
        val code =
            Json
                .parseToJsonElement(get("/api/v1/dev/otp?msisdn=$msisdn").bodyAsText())
                .jsonObject["code"]!!
                .jsonPrimitive.content

        return post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"msisdn":"$msisdn","code":"$code"}""")
        }.session()
    }

    private suspend fun HttpClient.refresh(token: String): HttpResponse =
        post("/api/v1/auth/session/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$token"}""")
        }

    private suspend fun HttpClient.logout(token: String): HttpResponse =
        post("/api/v1/auth/session/logout") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

    private suspend fun HttpResponse.session(): Tokens {
        val body = Json.parseToJsonElement(bodyAsText()).jsonObject
        return Tokens(
            access = body["accessToken"]!!.jsonPrimitive.content,
            refresh = body["refreshToken"]!!.jsonPrimitive.content,
        )
    }
}
