package io.konekt.e2e

import io.github.youndie.kompot.spec.KompotProtocol
import io.github.youndie.kompot.tck.RemoteTckTransport
import io.github.youndie.kompot.tck.TckConfig
import io.github.youndie.kompot.tck.TckResponse
import io.github.youndie.kompot.tck.TckRunner
import io.github.youndie.kompot.tck.TckTransport
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

    private companion object {
        // The kit's login address, taken from the plan so this file spells no address at all.
        val LOGIN_PATH: String =
            requireNotNull(KONEKT_WALK_PLAN.loginPath) {
                "the walk plan declares no login path, so the transport has nothing to adapt"
            }
    }

    @Test
    fun `the conformance walk visits what this deployment offers, and the verdict is read after that`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.creditAccount(session.subscriberId, majorUnits = 50)

                // A completed order, so that `/api/v1/screens/orders/{orderId}` is an address the walk
                // can reach at all. The kit cannot invent an identifier that exists, which is why
                // `pathParameters` is the application's to supply — and until something supplies one,
                // the largest component tree this server emits is walked by nothing.
                val orderId = completeOnePurchase(client, session)

                // A SECOND code for the kit's own login. The one `Stand.signIn` used is spent, and the
                // kit authenticates itself: handing it a token is not on offer, so the session it
                // walks with has to be one it obtained.
                val loginValues = freshLoginValues(client, session.msisdn)

                val document = openApiDocument()
                val endpoints = conformanceEndpoints(document)

                val report =
                    KonektLoginTransport(RemoteTckTransport(Stand.serverUrl, client), LOGIN_PATH).let { transport ->
                        TckRunner(transport, konektTckConfig(document, orderId, loginValues)).run()
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
            loginValues = loginValues,
            pathParameters =
                KONEKT_WALK_PLAN.pathParameters.mapValues { (_, placeholders) ->
                    placeholders.associateWith { name ->
                        when (name) {
                            "orderId" -> orderId

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

// THE ONE PLACE WHERE THIS SERVER'S LOGIN IS NOT A KOMPOT FORM, and the adapter that says so.
//
// `TckRunner.authenticate` posts a fixed submit envelope — `{formId, fieldId, values}` — to
// `TckConfig.loginPath`, because it assumes the way in is an ordinary kompot form. konekt's is not:
// `kompot-auth` is a single `update_session` action and nothing else, so OTP, tokens, refresh and
// logout are this product's own (research-architecture §1.5), and `POST /api/v1/auth/otp/verify`
// takes a plain `VerifyOtpRequest`. The kit offers no way to hand it a token instead.
//
// So the envelope is unwrapped here, at the seam the kit itself names as the only thing its checks
// know about transport. What is NOT done here is worth stating: no header is added and no response is
// touched. `securedEndpointsRejectAnonymous` asks a secured endpoint for a 401 with no token at all,
// and a transport that quietly carried one would turn that check green while proving the opposite.
private class KonektLoginTransport(
    private val delegate: TckTransport,
    private val loginPath: String,
) : TckTransport {
    override suspend fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String?,
    ): TckResponse {
        val rewritten =
            if (method == "POST" && path == loginPath && body != null) {
                Json
                    .parseToJsonElement(body)
                    .jsonObject["values"]
                    ?.jsonObject
                    ?.toString() ?: body
            } else {
                body
            }
        return delegate.request(method, path, headers, rewritten)
    }

    override suspend fun close() = delegate.close()
}
