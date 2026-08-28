package io.konekt.e2e

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotAction
import io.github.youndie.kompot.standard.ButtonComponent
import io.konekt.components.EsimQrComponent
import io.konekt.components.konektWalk
import io.konekt.feature.esim.shared.api.EsimInstallScreenResource
import io.konekt.feature.esim.shared.api.EsimWizardResource
import io.konekt.feature.esim.shared.api.EsimWizardStepAction
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// INSTALLING THE eSIM, WALKED THE WAY THE CLIENT WALKS IT — which is the whole point of this file
// existing, and the reason `B-66` reached a deployed contour with a green suite behind it.
//
// The client does not read the answer to a step. `EsimInstall.addressFor` posts the transition, checks
// the status, DISCARDS the body and answers with the screen's address so the holder refetches. So
// every assertion made on a step's own response is an assertion about a payload nothing renders.
//
// That is exactly where the defect lived: the step served over POST carried the issued profile and
// the same step served over GET did not, so the activate screen told subscribers their activation
// code could not be read while the database held it. Two green tests bracketed it — one read the POST,
// one covered which run a GET resumes — and neither formed the combination.
//
// The rule this file follows, therefore: POST to move, GET to look. Never assert on what a POST
// returned.
class EsimInstallScenarioTest {
    private val plan = "tr-10gb-30d"

    @Test
    fun `a subscriber who buys a plan can reach the activation code`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.topUp(client, session, majorUnits = 50)
                Stand.buyAndConfirm(client, session, plan)

                // Step one, as the install banner reaches it: an ordinary GET of an address.
                var screen = client.installScreen(session.accessToken)

                // Walked to the end rather than to the step under test, because "can they reach it"
                // is the question and a walk that stopped at the third step would answer a narrower
                // one. The bound is the flow's length plus slack; a wizard that stopped advancing
                // would otherwise spin here rather than fail.
                val codes = mutableListOf<String>()
                var steps = 0
                while (steps < WALK_LIMIT) {
                    screen.qrPayload()?.let { codes += it }

                    // The end of the walk is a step with nothing to go forward to, which is the last
                    // one. `break` and not a skip: a loop that keeps fetching a screen it cannot leave
                    // burns its whole budget and then reports the wrong thing.
                    val forward = screen.forwardAction() ?: break
                    client.step(session.accessToken, forward)
                    screen = client.installScreen(session.accessToken)
                    steps += 1
                }
                assertTrue(steps < WALK_LIMIT, "the walk never reached a last step; the wizard does not end")

                // THE ASSERTION. Not "the wizard advanced" — it always did — but that the one thing
                // the flow exists to hand over arrived, on a screen fetched the way the app fetches
                // it.
                val code = codes.firstOrNull()
                assertNotNull(
                    code,
                    "walked the whole install flow over GET and no activation code was ever drawn",
                )
                assertTrue(
                    code.startsWith("LPA:"),
                    "the code drawn is not an activation code a phone would take: $code",
                )
            }
        }

    // THE CASE A PERSON PRODUCES, and the one that was reported: they get as far as the code, put the
    // phone down, and the app fetches the screen again. Nothing between the two GETs but a request.
    @Test
    fun `coming back to the install screen still shows the code`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                Stand.topUp(client, session, majorUnits = 50)
                Stand.buyAndConfirm(client, session, plan)

                var screen = client.installScreen(session.accessToken)
                repeat(WALK_LIMIT) {
                    if (screen.qrPayload() != null) return@repeat
                    val forward = screen.forwardAction() ?: return@repeat
                    client.step(session.accessToken, forward)
                    screen = client.installScreen(session.accessToken)
                }
                val first =
                    assertNotNull(screen.qrPayload(), "never reached a step carrying the code")

                // The second arrival, with no step in between.
                val again = client.installScreen(session.accessToken)

                assertEquals(
                    first,
                    again.qrPayload(),
                    "the code was on the screen and re-fetching the same address lost it",
                )
            }
        }

    private suspend fun io.ktor.client.HttpClient.installScreen(token: String): KompotComponent =
        get(EsimInstallScreenResource()) {
            bearerAuth(token)
        }.let { Stand.json.decodeKompotComponent(it.bodyAsText()) }

    private suspend fun io.ktor.client.HttpClient.step(
        token: String,
        action: EsimWizardStepAction,
    ) {
        val response =
            post(EsimWizardResource.Step()) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                // Encoded through the application's `Json` and not the concrete serializer, which
                // writes the fields without the `type` discriminator and is answered 422 — the same
                // trap `EsimInstall` carries a paragraph about.
                setBody(Stand.json.encodeKompotAction(action))
            }
        assertTrue(response.status.isSuccess(), "the wizard refused a step: ${response.status}")
        // The body is deliberately not read. See the note on this class.
    }

    private fun KompotComponent.qrPayload(): String? =
        konektWalk().filterIsInstance<EsimQrComponent>().firstOrNull()?.payload

    // The forward control as the SCREEN names it, rather than a transition composed here: the server
    // puts the action on the button and the client posts it back unchanged, so reading it off the
    // tree is what the client does and a second copy of the graph is what this avoids.
    private fun KompotComponent.forwardAction(): EsimWizardStepAction? =
        konektWalk()
            .filterIsInstance<ButtonComponent>()
            .firstOrNull { it.id == "esim-wizard-next" }
            ?.action as? EsimWizardStepAction

    private companion object {
        // Four steps, and the slack is for a step that legitimately holds the run where it is.
        const val WALK_LIMIT = 8
    }
}
