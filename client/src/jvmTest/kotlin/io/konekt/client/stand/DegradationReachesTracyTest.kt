package io.konekt.client.stand

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.app.KonektApp
import io.konekt.client.app.KonektScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.net.konektHttpClient
import io.konekt.client.observability.KonektClientObservability
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.client.render.konektRegistry
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.konekt.time.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.workinprogress.katcher.Katcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.client.request.get as httpGet

// B-26'S THIRD ACCEPTANCE CRITERION, CARRIED FROM B-05, AND IT IS THE ONE THAT WAS UNAVAILABLE.
//
// The mechanism has existed since B-05: the renderer reports an unknown component through kompot's
// sink, once per component rather than once per redraw. Where the record GOES was the open half, and
// for one platform it was not a matter of writing code — tracy's agent published `jvm`, `linux_*` and
// `macos_arm64` and no iOS target at all, so the record had nowhere to go on the platform where an
// out-of-date build is likeliest. Filed as youndie/tracy#16; `0.1.13` publishes the three iOS targets.
//
// So this asserts at the FAR END rather than at the sink. `ClientAgainstStandTest` already proves the
// sink is called twice with the right type — that is a claim about a lambda. This one renders the
// same screen with a REAL tracy agent pointed at the stand's tracy, and then asks tracy whether the
// record arrived and whether it is findable by the wire type. A record tracy stores and nobody can
// count is what the same log line without `indexed = true` produces, and it looks identical from here.
@OptIn(ExperimentalTestApi::class)
class DegradationReachesTracyTest {
    private val baseUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"
    private val tracyUrl: String = System.getProperty("konekt.stand.tracy") ?: "http://127.0.0.1:8091"

    // The same pair the stand hands the server, because the client reports into the same tracy. A
    // second ingest key would be a second thing to keep in step for no reason.
    private val tracyIngest: String = System.getProperty("konekt.stand.tracy.ingest") ?: "http://127.0.0.1:8091"
    private val tracyKey: String = System.getProperty("konekt.stand.tracy.key") ?: "dev-tracy-key"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

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

    @Test
    fun `an unknown component reaches tracy findable by its wire type, and leaves a breadcrumb`() {
        val http = signedInClient()
        val observability =
            KonektClientObservability.of(
                endpoint = tracyIngest,
                apiKey = tracyKey,
                release = "stand-test",
                instanceId = "client-stand",
                scope = scope,
                clock = SystemClock,
            )
        observability.start()

        // THE BASELINE, READ BEFORE ANYTHING IS RENDERED, and this test was vacuous without it.
        //
        // tracy's service row is CUMULATIVE. Asserting that `entityRefs.originalType` is above zero
        // passes on the evidence of any earlier run against the same stand — which was proved rather
        // than suspected: with `indexed = true` removed the assertion still passed, because the refs
        // from the previous run were still there. A collector that accumulates cannot be asked
        // "did it happen"; it can only be asked "did it happen again".
        val baseline = runBlocking { originalTypeRefs() }

        // katcher's breadcrumb list is a process-wide ring buffer, cleared for the same reason: a
        // test that read one somebody else left would pass without the mechanism working at all.
        Katcher.clearBreadcrumbs()

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens =
                        KonektScreenSource(
                            http = http,
                            realtime = SseRealtimeSource(http, konektClientJson),
                            registry = konektRegistry(),
                            json = konektClientJson,
                            onAction = { },
                        ),
                    address = "/api/v1/dev/screens/forward-compat",
                    topic = "stand",
                    darkMode = false,
                    onDegradation = observability.recorder(),
                )
            }

            // The screen's two unknown components produce two breadcrumbs. Waited for on the
            // BREADCRUMB rather than on the tracy record, because this half is synchronous and the
            // other is not — and waiting on the slow one first would hide a fast one that never came.
            waitUntil(timeoutMillis = 20_000) {
                Katcher.breadcrumbs.count { it.type == "degradation" } >= 3
            }
        }

        val crumbs = Katcher.breadcrumbs.filter { it.type == "degradation" }
        // THREE, because the screen carries three components this build cannot render: two it cannot
        // decode and one it decodes and has no renderer for (B-44). All three leave a crumb, and all
        // three name their type — which is the only thing a crumb is for.
        assertEquals(3, crumbs.size, "expected one breadcrumb per component that could not be rendered: $crumbs")
        assertEquals(
            setOf("esim_transfer_widget", "step_meter"),
            crumbs.mapNotNull { it.data?.get("originalType") }.toSet(),
            "a breadcrumb lost the wire type: ${crumbs.map { it.data }}",
        )
        // A crash report carries breadcrumbs so somebody can read what happened BEFORE it. A crumb
        // that cannot say which type went unrendered is a line of prose in a list of prose.
        assertTrue(
            crumbs.all { it.data?.get("drawnAsFallback") == "false" },
            "a placeholder was recorded as a substitution: ${crumbs.map { it.data }}",
        )

        // AND THE FAR END, MEASURED AS GROWTH. tracy's delivery flushes on a tick, so this waits on
        // the collector rather than on the agent — the difference between "the record was written" and
        // "the record arrived" is the whole reason this test talks to tracy at all.
        //
        // An entity ref exists only because the field was written with `indexed = true`; the same line
        // without the flag produces a record tracy stores and nobody can find by the type it is about.
        // Two new refs, because the screen carries two unknown components — "more than before" would
        // pass on one of them arriving, which is the half-delivered case worth telling apart.
        runBlocking {
            val grown =
                awaitOrFail("tracy to index three more originalType refs than the $baseline it already had") {
                    originalTypeRefs().takeIf { it >= baseline + 3 }
                }

            assertTrue(grown >= baseline + 3, "expected three new refs, went from $baseline to $grown")
        }
    }

    // How many degradations tracy can find BY WIRE TYPE, for this client, right now. Zero when the
    // service has never reported and zero when it reported without indexing — different failures with
    // the same number, which is why the delta rather than the value is what gets asserted.
    private suspend fun originalTypeRefs(): Int {
        val body =
            HttpClient(CIO.create()).use { plain ->
                plain
                    .httpGet("$tracyUrl/api/services") { header("X-Auth-Request-User", "stand") }
                    .bodyAsText()
            }
        val row =
            services(body).firstOrNull {
                it["name"]?.jsonPrimitive?.content == KonektClientObservability.SERVICE
            } ?: return 0
        return row["entityRefs"]
            ?.jsonObject
            ?.get("originalType")
            ?.jsonPrimitive
            ?.content
            ?.toIntOrNull() ?: 0
    }

    private fun services(body: String): List<JsonObject> {
        val parsed = Json.parseToJsonElement(body)
        val array = (parsed as? JsonArray) ?: (parsed.jsonObject["services"]?.jsonArray ?: JsonArray(emptyList()))
        return array.map { it.jsonObject }
    }

    // Bounded, and it names what it waited for. A bare timeout on a collector is the least useful
    // sentence a test can end with: the stand fails in ways that are not this test's subject.
    private suspend fun <T : Any> awaitOrFail(
        what: String,
        attempt: suspend () -> T?,
    ): T {
        repeat(ATTEMPTS) {
            runCatchingQuietly(attempt)?.let { return it }
            delay(POLL_MS)
        }
        throw AssertionError("waited ${ATTEMPTS * POLL_MS / 1000}s for: $what")
    }

    // A precise catch rather than `runCatching`: the stand being briefly away is the ordinary case
    // here, and a cancellation is not something to swallow while polling.
    private suspend fun <T : Any> runCatchingQuietly(attempt: suspend () -> T?): T? =
        try {
            attempt()
        } catch (failure: java.io.IOException) {
            null
        }

    private companion object {
        const val ATTEMPTS = 40
        const val POLL_MS = 500L
    }
}
