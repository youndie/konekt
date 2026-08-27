package io.konekt.e2e

import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// THE ONE THING AN OBSERVABILITY AGENT CANNOT PROVE ABOUT ITSELF.
//
// All three of these libraries answer a missing endpoint, a missing key or an unreachable collector
// by doing nothing: metrik's plugin has an `enabled` flag, tracy's delivery never connects, katcher's
// `start` prints a line and returns. A deployment that MEANT to be observed and is silent looks
// exactly like one that is working — from inside. tracy's own source carries the sentence about it:
// "metrik lost months to exactly that class of failure."
//
// So the assertion is at the FAR END. Drive real traffic through the stand, then ask each collector
// whether anything arrived, and fail on zero. That is B-26's second acceptance criterion stated as a
// test rather than as an intention.
class ObservabilityScenarioTest {
    private val metrikUrl: String = System.getProperty("konekt.stand.metrik") ?: "http://127.0.0.1:8190"
    private val tracyUrl: String = System.getProperty("konekt.stand.tracy") ?: "http://127.0.0.1:8191"
    private val katcherUrl: String = System.getProperty("konekt.stand.katcher") ?: "http://127.0.0.1:8192"

    private val service = "konekt-server"

    // The id the stand's seed gives konekt-server, which inserts it first. A literal rather than a
    // lookup: the seed is the only thing that writes these rows and it writes them in one order, so a
    // parse of the apps page would be indirection over a constant this repository owns.
    private val konektServerApp = 1

    // BOTH HEADERS, and one is not enough — the second is what took a 401 to find out. katcher's
    // header auth wants a name AND an email, and creates the user from them, so nothing has to be
    // provisioned for the read path.
    private fun io.ktor.client.request.HttpRequestBuilder.katcherUser() {
        header("X-Auth-Request-User", "e2e")
        header("X-Auth-Request-Email", "e2e@konekt.local")
    }

    private val plan = "tr-10gb-30d"

    @Test
    fun `a purchase is visible in metrik as latency and in tracy as an order`() =
        runBlocking {
            val orderId =
                Stand.client().use { client ->
                    val session = Stand.signIn(client)
                    Stand.topUp(client, session, majorUnits = 50)

                    client
                        .post(Purchases()) {
                            bearerAuth(session.accessToken)
                            setBody(CreatePurchaseRequest(plan))
                        }.body<PurchaseOrderResponse>()
                        .orderId
                }

            assertTrue(orderId.isNotBlank())

            // Both agents batch on a window — metrik's is a second, tracy's flush interval is a
            // second — so the wait is the agents' own cadence rather than a guess. `awaitOrExplain`
            // names what the stand looks like when it runs out, which is the difference between "the
            // agent is broken" and "the collector never started".
            Stand.client(metrikUrl).use { client ->
                val service =
                    Stand.awaitOrExplain(
                        what = "metrik to have seen $service",
                        timeout = 20.seconds,
                    ) {
                        collectors(client, "$metrikUrl/api/services").firstOrNull { it.name == service }
                    }

                // Not merely present: PRESENT AND NON-ZERO. A service row can be created by a
                // handshake, and a row with no requests is what an agent that connected and then
                // reported nothing produces.
                assertTrue(
                    service.number("requestsPerSecond") > 0.0,
                    "metrik knows $service and has counted no requests: $service",
                )
            }

            Stand.client(tracyUrl).use { client ->
                val seen =
                    Stand.awaitOrExplain(
                        what = "tracy to have stored a record for $service",
                        timeout = 20.seconds,
                    ) {
                        collectors(client, "$tracyUrl/api/services")
                            .firstOrNull { it.name == service && it.number("storedRecords") > 0.0 }
                    }

                // THE ASSERTION THE WHOLE ITEM IS FOR. An entity ref is what makes "show me
                // everything that happened to this order" answerable, and it exists only because the
                // field was written with `indexed = true`. The same log line without that flag
                // produces a record tracy stores and nobody can find.
                val refs = seen.raw["entityRefs"]?.jsonObject
                assertNotNull(refs, "tracy stored records for $service and indexed nothing: ${seen.raw}")
                assertTrue(
                    (refs["orderId"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) > 0.0,
                    "tracy has no orderId entity refs, so no trace is reachable by an order: $refs",
                )
            }
        }

    @Test
    fun `a route that throws answers the error contract rather than leaking the exception`(): Unit =
        runBlocking {
            // THE HALF OF B-26's FIRST CRITERION THAT HAD NOTHING TO EXERCISE IT. "A katcher report if
            // the route throws" needed a route that throws, and until `/api/v1/dev/fail` nothing in
            // this stand failed on purpose — so what was checked was that katcher's address is not a
            // 404, which is also true of an address nobody posts to.
            //
            // Adding the route found the real defect immediately, and it was not the missing route:
            // `Katcher.start` installs an uncaught-exception handler, `StatusPages` catches every
            // route exception before one can run, and so a correctly configured katcher was
            // structurally unable to receive anything a route did. The handler reports explicitly now.
            Stand.client().use { client ->
                val response = client.get("${Stand.serverUrl}/api/v1/dev/fail")

                assertEquals(500, response.status.value)
                val body = response.bodyAsText()
                // The error CONTRACT, not the exception. An unexpected failure's message is written
                // for whoever wrote the code — it carries identifiers and sometimes a query — and a
                // subscriber is not that reader.
                assertTrue("internal_error" in body, "the failing route did not answer the error contract: $body")
                assertTrue(
                    "deliberate failure" !in body,
                    "the exception message reached the client: $body",
                )
            }
        }

    @Test
    fun `a route that throws produces a crash report katcher can show`(): Unit =
        runBlocking {
            // B-26'S FIRST ACCEPTANCE CRITERION, ALL THREE PARTS, and the katcher third took the
            // longest to become assertable at all. What stood in the way was never one thing:
            //
            //   1. nothing threw on purpose. `/api/v1/dev/fail` does.
            //   2. `Katcher.start` installs an uncaught-exception handler and `StatusPages` catches
            //      every route exception before one can run, so a correctly configured katcher was
            //      structurally unable to receive anything. The handler reports explicitly now.
            //   3. a fresh katcher has no application and no key, so an ingest naming `konekt-server`
            //      was refused. The stand seeds both.
            //
            // Each of those was invisible from the layer above it, and the address check this replaces
            // was satisfied by all three being broken at once.
            Stand.client().use { client ->
                assertEquals(500, client.get("${Stand.serverUrl}/api/v1/dev/fail").status.value)
            }

            Stand.client(katcherUrl).use { client ->
                val page =
                    Stand.awaitOrExplain(
                        what = "katcher to show the crash the failing route produced",
                        timeout = 30.seconds,
                    ) {
                        // The application's own page, read the way a person reads it. katcher exposes
                        // no JSON for this — `/api` carries the ingest and nothing else — so the far
                        // end of this pipeline is HTML, and asserting on it is asserting on what an
                        // operator would actually see.
                        val body = client.get("$katcherUrl/apps/$konektServerApp/errors") { katcherUser() }.bodyAsText()
                        body.takeIf { "deliberate failure" in it }
                    }

                // Not merely that something arrived: THIS failure, named by the class that threw and
                // by the file it threw in. A report carrying somebody else's stack would satisfy a
                // count and tell an operator nothing.
                assertTrue("IllegalStateException" in page, "the report lost the exception type")
                assertTrue("FailingRouting" in page, "the report lost the frame that threw")
            }
        }

    @Test
    fun `katcher's ingest is a real endpoint rather than a name in a config`() =
        runBlocking {
            // The weakest of the three assertions, and it says so. A crash report needs something to
            // crash, and nothing in this stand throws on purpose yet — so what is checked here is that
            // the address the server is configured against ANSWERS, which is the failure that would
            // otherwise be discovered by a crash going nowhere.
            Stand.client(katcherUrl).use { client ->
                val response = client.get("$katcherUrl/api/reports") { header("X-Auth-Request-User", "e2e") }

                // 405: the route exists and takes POST. A 404 would mean the server is reporting into
                // an address that is not there.
                assertEquals(405, response.status.value, "katcher's ingest did not answer: ${response.bodyAsText()}")
            }
        }

    private class Row(
        val raw: JsonObject,
    ) {
        val name: String get() = raw["name"]?.jsonPrimitive?.content.orEmpty()

        fun number(field: String): Double = raw[field]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

        override fun toString() = raw.toString()
    }

    private suspend fun collectors(
        client: io.ktor.client.HttpClient,
        url: String,
    ): List<Row> {
        // The read API of both sits behind a reverse proxy in a real deployment and trusts
        // `X-Auth-Request-User`. There is no proxy in the stand, so this header is simply believed —
        // which is fine for reading a stand and would not be fine for anything else.
        val body = client.get(url) { header("X-Auth-Request-User", "e2e") }.bodyAsText()
        val parsed = Stand.json.parseToJsonElement(body)
        return (parsed as? JsonArray).orEmpty().map { Row(it.jsonObject) }
    }
}
