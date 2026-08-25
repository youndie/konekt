package io.konekt.e2e

import io.github.youndie.kompot.spec.KompotProtocol
import io.github.youndie.kompot.tck.RemoteTckTransport
import io.github.youndie.kompot.tck.TckConfig
import io.github.youndie.kompot.tck.TckRunner
import io.konekt.components.konektWireNames
import io.konekt.conformance.KONEKT_WALK_PLAN
import io.konekt.conformance.assertTheWalkVisitedEveryTarget
import io.konekt.conformance.conformanceEndpoints
import io.konekt.conformance.tckTargets
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.openapi.OpenApiFiles
import io.konekt.spec.KonektSpec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

// THE CONFORMANCE WALK, and it lives beside the stand rather than in `:server:test` on purpose.
//
// `:server:test` already carries the other half — a gate over the committed OpenAPI document that
// asks what a walk of this deployment WOULD have to visit, per check and per endpoint. That half
// needs no stand and runs on every build. This half is the run itself, and a run is about a
// deployment: a walk of an object graph a test assembled answers about that graph, and the four
// defects the stand found on its first boot are what a graph a test assembles cannot see.
//
// The order below is the item's, and it is not decorative. Coverage is asserted BEFORE the verdict,
// because a verdict over an empty set is not a verdict — `check(report.isClean)` is what the kit's
// own readme shows and it is green on a server whose screens the walk never reached.
class TckWalkTest {
    private val plan = "tr-10gb-30d"

    @Test
    fun `the conformance walk visits what this deployment offers, and the verdict is read after that`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                // A top-up and a completed order, so that the three templated addresses are
                // reachable at all. The kit cannot invent an identifier that exists, which is why
                // `pathParameters` is the application's to supply — and until something supplies one,
                // the largest component tree this server emits is walked by nothing.
                //
                // The money goes in through the product's own path. Before B-40 that was an UPDATE at
                // the stand's database, so there was no top-up to name either.
                val topUpId = Stand.topUp(client, session, majorUnits = 50).topUpId
                val orderId = completeOnePurchase(client, session)

                // A SECOND code for the kit's own login. The one `Stand.signIn` used is spent, and the
                // kit authenticates itself: handing it a token is not on offer, so the session it
                // walks with has to be one it obtained.
                val loginValues = freshLoginValues(client, session.msisdn)

                val document = openApiDocument()
                val endpoints = conformanceEndpoints(document)

                val report =
                    RemoteTckTransport(Stand.serverUrl, client).let { transport ->
                        TckRunner(transport, konektTckConfig(document, orderId, topUpId, loginValues)).run()
                    }

                // Coverage first, per check and per endpoint. Never as a sum: a sum is satisfied by the
                // checks that do have targets here while the others see nothing at all.
                assertTheWalkVisitedEveryTarget(tckTargets(endpoints, KONEKT_WALK_PLAN), report.exercised)

                // And only now the verdict.
                assertTrue(report.isClean, report.toString())
            }
        }

    private suspend fun completeOnePurchase(
        client: HttpClient,
        session: Stand.Session,
    ): String {
        val started =
            client
                .post(Purchases()) {
                    bearerAuth(session.accessToken)
                    setBody(CreatePurchaseRequest(plan))
                }.body<PurchaseOrderResponse>()

        client.post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
            bearerAuth(session.accessToken)
        }

        return started.orderId
    }

    private suspend fun freshLoginValues(
        client: HttpClient,
        msisdn: String,
    ): Map<String, JsonPrimitive> {
        client.post(AuthOtp.Request(AuthOtp())) { setBody(RequestOtpRequest(msisdn)) }
        val code = client.get(DevOtp(msisdn)).body<DevOtpResponse>().code
        return mapOf("msisdn" to JsonPrimitive(msisdn), "code" to JsonPrimitive(code))
    }

    private fun openApiDocument(): JsonObject {
        val path = Path(OpenApiFiles.PATH)
        assertTrue(path.exists(), "no committed document at ${OpenApiFiles.PATH}; the walk would be about nothing")
        return Json.parseToJsonElement(path.readText()).jsonObject
    }

    private fun konektTckConfig(
        document: JsonObject,
        orderId: String,
        topUpId: String,
        loginValues: Map<String, JsonPrimitive>,
    ): TckConfig =
        TckConfig(
            schemas =
                KonektSpec.generateAll().associate { it.fileName to it.document } +
                    (KompotProtocol.PROFILE_FILE_NAME to KonektSpec.profile()),
            openApi = document,
            // DERIVED FROM THE PLAN, never written a second time. `KONEKT_WALK_PLAN` declares which
            // addresses are supplied and which placeholders have a value; this supplies the values.
            // Written twice they would drift, and the drift would be invisible in exactly one
            // direction — a walk that reaches MORE than the plan expects makes the coverage assertion
            // under-claim while staying green.
            loginPath = KONEKT_WALK_PLAN.loginPath,
            // THE WHOLE BODY, VERBATIM, because this server's login is not a kompot form. The toolkit
            // never required it to be one — `kompot-auth` is a single `update_session` action and
            // everything around it is the application's — so konekt's OTP exchange takes a plain
            // `VerifyOtpRequest`.
            //
            // `loginBody` rather than `bearerToken`, and the difference is a check. Handing the kit a
            // session skips the exchange entirely: nothing then verifies that the login answers an
            // `update_session` carrying an `accessToken`, which is the part §12 makes a rule. This way
            // the kit still performs the login and still holds it to that.
            //
            // Both arrived in 0.32.0.77 (youndie/kompot#85), which replaced a `TckTransport` decorator
            // here that unwrapped the envelope on one path — a rule about a request BODY living in the
            // layer documented as the only thing the checks know about transport.
            loginBody = JsonObject(loginValues),
            pathParameters =
                KONEKT_WALK_PLAN.pathParameters.mapValues { (_, placeholders) ->
                    placeholders.associateWith { name ->
                        when (name) {
                            "orderId" -> orderId

                            "topUpId" -> topUpId

                            // A placeholder added to the plan and not given a value here would
                            // otherwise be substituted with the word "orderId" and produce a 404 that
                            // reads like a server defect.
                            else -> error("the walk plan asks for a value for \"$name\" and nothing supplies one")
                        }
                    }
                },
            // DECLARED and not inferred: the check "the server keeps to what it declared" means
            // nothing if the kit reads the declaration off the responses it is checking. These are the
            // nine component types of `:shared:components` plus the eSIM wizard's one action, which is
            // konekt's whole application-level verb set.
            extensionTypes = konektWireNames.toSet() + "esim_wizard_step",
            // Off. The idempotency check performs a REAL operation, and konekt implements no
            // Idempotency-Key contract for it to exercise — see B-24 for the item that would give it
            // one.
            allowStateChangingChecks = KONEKT_WALK_PLAN.allowStateChangingChecks,
        )
}
