package io.konekt.feature.esim.server.domain

import io.github.youndie.kompot.wizard.core.WizardTransition
import io.konekt.domain.KonektException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The rules the graph cannot express, which is all of them: wizard-core models where a Next leads
// and has no notion of a Next that must not happen.
class AdvanceEsimWizardUseCaseTest {
    private val subscriber = "sub-1"

    private val sessions = FakeSessions()
    private val esims = FakeEsims()
    private val smDpPlus = FakeSmDpPlus(refusalText = "This device already holds 8 eSIM profiles.")

    private val start = StartEsimWizardUseCase(sessions, esims, EsimIds { "wiz-1" })
    private val advance = AdvanceEsimWizardUseCase(sessions, esims, smDpPlus)

    private suspend fun startRun() = start(StartEsimWizardUseCase.Params(subscriber)).getOrThrow()

    private suspend fun step(transition: WizardTransition) =
        advance(
            AdvanceEsimWizardUseCase.Params(
                wizardId = "wiz-1",
                subscriberId = subscriber,
                transition = transition,
            ),
        ).getOrThrow()

    @Test
    fun `the slot limit holds the run on step one and says why`() =
        runTest {
            esims.held = 8
            startRun()

            val view = step(WizardTransition.Next)

            // The two halves of the acceptance criterion. The run did not move —
            assertEquals(EsimWizardSteps.CHECK, view.record.session.currentStepId)
            // — and the reason travels with the step rather than as a status code, so the client has
            // a screen to draw rather than a 409 to interpret.
            val refusal = assertNotNull(view.refusal, "the slot limit produced no refusal")
            assertEquals(EsimRefusals.SLOT_LIMIT, refusal.code)
            assertEquals("This device already holds 8 eSIM profiles.", refusal.text)

            // And nothing was ordered from the manager on the way.
            assertEquals(0, smDpPlus.issued)
        }

    @Test
    fun `one profile below the limit still advances`() =
        runTest {
            // The boundary, from the side that must work. A limit written with the wrong comparison
            // refuses the eighth profile to somebody holding seven, and the refusal reads exactly
            // like a correct one.
            esims.held = 7
            startRun()

            val view = step(WizardTransition.Next)

            assertNull(view.refusal)
            assertEquals(EsimWizardSteps.CONFIRM, view.record.session.currentStepId)
        }

    @Test
    fun `back is never refused`() =
        runTest {
            esims.held = 0
            startRun()
            step(WizardTransition.Next)

            // The slots fill up while the run is open — another device, another tab.
            esims.held = 8

            val view = step(WizardTransition.Back)

            // A refusal that blocked Back as well would be a wizard with no way out but closing the
            // application.
            assertNull(view.refusal)
            assertEquals(EsimWizardSteps.CHECK, view.record.session.currentStepId)
        }

    @Test
    fun `a profile is issued exactly once, however many times the step is entered`() =
        runTest {
            startRun()
            // check -> confirm -> activate. The profile is issued on the way INTO activate, so it is
            // the second Next that costs something outside the process.
            step(WizardTransition.Next)

            val first = assertNotNull(step(WizardTransition.Next).esim)
            assertEquals(
                EsimWizardSteps.ACTIVATE,
                sessions.rows
                    .getValue("wiz-1")
                    .session.currentStepId,
            )

            // Back and forward again: the same arrival, from a subscriber who wanted to re-read the
            // step before it. A retried request or a double tap looks identical from here.
            step(WizardTransition.Back)
            val second = assertNotNull(step(WizardTransition.Next).esim)

            assertEquals(1, smDpPlus.issued, "the manager was asked for a second profile")
            assertEquals(1, esims.created.size)
            assertEquals(first.id, second.id)
        }

    @Test
    fun `the activation code the view carries is the one that was issued`() =
        runTest {
            startRun()
            repeat(3) { step(WizardTransition.Next) }

            val view = step(WizardTransition.Next)
            val esim = assertNotNull(view.esim)

            assertEquals(esims.created.single().activationCode, esim.activationCode)
            assertTrue(
                esim.activationCode!!.startsWith("LPA:1\$"),
                "not an LPA activation code: ${esim.activationCode}",
            )
        }

    @Test
    fun `finishing the last step marks the profile installed`() =
        runTest {
            startRun()
            repeat(3) { step(WizardTransition.Next) }

            val view = step(WizardTransition.Finish)

            assertTrue(view.record.session.isFinished)
            assertEquals(listOf(esims.created.single().id), esims.installed)
        }

    @Test
    fun `a run abandoned before a profile exists marks nothing`() =
        runTest {
            startRun()

            step(WizardTransition.Finish)

            // Finish may be sent from any step, and the marker must not run against a profile that
            // was never issued — which as a null id would be an update matching no rows, silently.
            assertTrue(esims.installed.isEmpty())
        }

    @Test
    fun `a finished run answers with itself rather than an error`() =
        runTest {
            startRun()
            step(WizardTransition.Finish)

            val again = step(WizardTransition.Next)

            // The client may still be holding the last screen. Answering 404 to the button on it
            // would replace a finished wizard with a failure.
            assertTrue(again.record.session.isFinished)
            assertNull(again.refusal)
        }

    @Test
    fun `somebody else's run is not found rather than forbidden`() =
        runTest {
            startRun()

            val failure =
                advance(
                    AdvanceEsimWizardUseCase.Params("wiz-1", "sub-2", WizardTransition.Next),
                ).exceptionOrNull()

            // 404 and not 403: a 403 would confirm the run exists, which is an enumeration oracle for
            // anyone who wants one.
            assertTrue(failure is KonektException.NotFound, "got $failure")
        }
}
