package io.konekt.e2e

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.auth.kompotAuthSerializersModule
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import java.sql.DriverManager
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// What the suite talks to, and what it says when the stand is the thing that is wrong.
//
// A stand fails in ways a unit test cannot: a container that did not come up, a broker that died
// halfway, an image built from a distribution nobody rebuilt. Every one of those surfaces here as a
// wait that expires, and a bare timeout is the least useful sentence a test can end with. So the
// waiting is in one place and it ASKS THE STAND WHAT HAPPENED before it gives up.
object Stand {
    val serverUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"
    val decliningUrl: String = System.getProperty("konekt.stand.declining") ?: "http://127.0.0.1:8081"
    private val jdbcUrl: String = System.getProperty("konekt.stand.jdbc") ?: "jdbc:postgresql://127.0.0.1:55432/konekt"
    private val composeFile: String? = System.getProperty("konekt.stand.compose")

    // The same module set the server assembles. Two lists that must agree and cannot share a
    // definition is a seam that drifts — and this suite is the one place where a drift between them
    // would be visible as a screen that decodes to nothing.
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule +
                kompotStandardSerializersModule +
                generatedStandardSerializersModule +
                generatedKonektSerializersModule +
                kompotAuthSerializersModule
        }

    fun client(baseUrl: String = serverUrl): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
            install(SSE)
            defaultRequest {
                url(baseUrl)
                contentType(ContentType.Application.Json)
            }
        }

    // A number nobody else in the run is using. The stand keeps its database between scenarios on
    // purpose — a suite that truncates is a suite that cannot show two subscribers — so isolation is
    // by identity rather than by cleaning.
    fun freshMsisdn(): String = "1555${(1_000_000..9_999_999).random()}"

    class Session(
        val msisdn: String,
        val subscriberId: String,
        val accessToken: String,
    )

    suspend fun signIn(client: HttpClient): Session {
        val msisdn = freshMsisdn()

        client.post(AuthOtp.Request(AuthOtp())) { setBody(RequestOtpRequest(msisdn)) }

        // The development endpoint that reads back what the SMSC would have carried. The boundary of
        // this system stops at the SMSC, so without it there is no way to sign in at all.
        val code = client.get(DevOtp(msisdn)).body<DevOtpResponse>().code

        val answer = client.post(AuthOtp.Verify(AuthOtp())) { setBody(VerifyOtpRequest(msisdn, code)) }
        val action = json.decodeKompotAction(answer.bodyAsText())

        check(action is UpdateSessionAction) { "verify answered ${action::class.simpleName}, not a session" }

        return Session(msisdn = msisdn, subscriberId = subscriberIdOf(msisdn), accessToken = action.accessToken)
    }

    // SEEDED IN SQL, because the product has no way to add money: a subscriber is created with zero
    // and nothing tops it up (B-40). Reaching into the stand's own database is honest for a stand;
    // adding a development-only top-up endpoint so that a test has something to call would be a
    // production surface invented for a test.
    fun creditAccount(
        subscriberId: String,
        majorUnits: Long,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement("UPDATE account SET balance_minor = ? WHERE subscriber_id = ?")
                .use { statement ->
                    statement.setLong(1, majorUnits * 100)
                    statement.setString(2, subscriberId)
                    check(statement.executeUpdate() == 1) { "no account for $subscriberId" }
                }
        }
    }

    private fun subscriberIdOf(msisdn: String): String =
        connection().use { connection ->
            connection.prepareStatement("SELECT id FROM subscriber WHERE msisdn = ?").use { statement ->
                statement.setString(1, msisdn.trimStart('+'))
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no subscriber for $msisdn — did verify create one?" }
                    rows.getString(1)
                }
            }
        }

    private fun connection() = DriverManager.getConnection(jdbcUrl, "konekt", "konekt")

    // The wait that names what went wrong.
    //
    // Every scenario here crosses several processes, so an expired wait means one of them is not
    // doing its job — and which one is a question the harness can answer and a person should not have
    // to. `docker compose ps` is the answer: the broker publishes no port and cannot be probed
    // directly, and it is the process whose death is quietest.
    suspend fun <T : Any> awaitOrExplain(
        what: String,
        timeout: Duration = 45.seconds,
        poll: Duration = 500.milliseconds,
        attempt: suspend () -> T?,
    ): T {
        val started = TimeSource.Monotonic.markNow()
        var lastFailure: Throwable? = null

        while (started.elapsedNow() < timeout) {
            try {
                attempt()?.let { return it }
            } catch (failure: Exception) {
                lastFailure = failure
            }
            kotlinx.coroutines.delay(poll)
        }

        throw AssertionError(
            buildString {
                append("waited $timeout for: $what")
                lastFailure?.let { append("\n  last failure: ${it::class.simpleName}: ${it.message}") }
                append("\n\n")
                append(standDiagnosis())
            },
        )
    }

    // What the stand looks like right now, in the failure message rather than in a log somebody has
    // to think to open.
    fun standDiagnosis(): String {
        val file = composeFile ?: return "  (no compose file configured, so the stand cannot be inspected)"

        val output =
            try {
                ProcessBuilder("docker", "compose", "-f", file, "ps", "--format", "{{.Service}} {{.State}} {{.Status}}")
                    .redirectErrorStream(true)
                    .start()
                    .let { process ->
                        val text = process.inputStream.bufferedReader().readText()
                        process.waitFor()
                        text
                    }
            } catch (unavailable: Exception) {
                return "  (docker could not be asked: ${unavailable.message})"
            }

        val lines = output.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return "  THE STAND IS NOT RUNNING — nothing is up. Start it: make stand-up"

        // A service is fine if it is running, or if it exited cleanly — the migration job is meant to
        // exit, and exiting with anything but 0 is exactly the case worth reporting. Parenthesised
        // because a mixed `&&`/`||` is where a precedence mistake hides, and this line decides what a
        // failure message accuses.
        val fine = { line: String -> line.contains(" running") || (line.contains(" exited") && line.contains("(0)")) }
        val down = lines.filterNot(fine)

        return buildString {
            appendLine("  the stand right now:")
            lines.forEach { appendLine("    $it") }
            if (down.isNotEmpty()) {
                appendLine()
                appendLine("  NOT RUNNING: ${down.joinToString("; ") { it.substringBefore(' ') }}")
                appendLine("  That, and not the timeout, is what this run actually hit.")
            }
        }
    }
}
