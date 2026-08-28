package io.konekt.feature.esim.server.domain

import io.github.youndie.kompot.wizard.core.WizardTransition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// THE SAME STEP, REACHED TWO WAYS, MUST BE THE SAME SCREEN.
//
// There are two ways into any step of the install flow. Stepping into it answers a view; opening the
// screen's address answers a view of wherever the run already is. Both build the tree the subscriber
// sees, and for a long time only one of them carried the issued profile — so the ACTIVATE step drew
// its QR when it was stepped into and said "we could not read your activation code" when it was
// opened, over a database row holding one (`B-66`).
//
// That combination is what nothing tested. `AdvanceEsimWizardUseCaseTest` reads the answer to a step,
// which was always right; `OpenEsimWizardUseCaseTest` covered which RUN is resumed, which was also
// right. The defect lived in the one cell of the grid neither of them formed — and it was the cell
// the client uses, because it posts a step, discards the answer and refetches the address.
//
// SO THIS ASSERTS OVER EVERY STEP RATHER THAN OVER THE ONE THAT BROKE. Only ACTIVATE has a profile to
// lose today; the step that gains something to lose next is the step nobody will remember to add here.
class EsimWizardViewsAgreeTest {
    private class Stand {
        val sessions = FakeSessions()
        val esims = FakeEsims()
        val smDpPlus = FakeSmDpPlus()
        private var n = 0

        val open = OpenEsimWizardUseCase(sessions, esims, EsimIds { "wizard-${++n}" })
        val advance = AdvanceEsimWizardUseCase(sessions, esims, smDpPlus)

        suspend fun step(
            wizardId: String,
            transition: WizardTransition = WizardTransition.Next,
        ) = advance(AdvanceEsimWizardUseCase.Params(wizardId, SUBSCRIBER, transition)).getOrThrow()

        suspend fun open() = open(OpenEsimWizardUseCase.Params(SUBSCRIBER)).getOrThrow()
    }

    @Test
    fun `opening a run answers the same profile as stepping into it, at every step`() =
        runTest {
            val stand = Stand()
            val visited = mutableListOf<String>()

            var stepped = stand.open()
            while (true) {
                val step = stepped.record.session.currentStepId
                visited += step

                // The comparison. `esim` is the whole of what one path used to drop, and the step id
                // is here so a disagreement about WHERE the run is cannot masquerade as agreement
                // about what it holds.
                val opened = stand.open()
                assertEquals(
                    step,
                    opened.record.session.currentStepId,
                    "opening answered a different step than the one the run is on",
                )
                assertEquals(
                    stepped.esim,
                    opened.esim,
                    "at step '$step' the two ways into the same screen disagree about the profile",
                )

                if (step == EsimWizardSteps.DONE) break
                stepped = stand.step(stepped.record.id)
            }

            // VACUITY, in two directions. A walk that stopped early would compare a prefix and pass;
            // a flow that gained a step would leave this walking the old one.
            assertEquals(
                EsimWizardSteps.order,
                visited,
                "the walk did not visit every step of the flow, so the steps it missed are unchecked",
            )
            // And the positive control that gives the comparison something to be about: by ACTIVATE a
            // profile exists. Without this the test above passes on a flow that issues nothing, where
            // both paths agree on null at every step.
            assertTrue(stand.esims.created.isNotEmpty(), "no profile was ever issued; both sides agreed on nothing")
            assertNotNull(stepped.esim, "the run reached the end holding no profile")
        }

    // The case a subscriber actually produces: they walk to the code, put the phone down, and come
    // back to a fresh fetch of the address. Written separately from the loop above because it is the
    // scenario rather than the invariant, and it is the one that was reported.
    @Test
    fun `a subscriber who comes back to the activate step still sees their code`() =
        runTest {
            val stand = Stand()

            val opened = stand.open()
            stand.step(opened.record.id) // check -> confirm
            val activate = stand.step(opened.record.id) // confirm -> activate, issuing the profile
            assertEquals(EsimWizardSteps.ACTIVATE, activate.record.session.currentStepId)

            // Nothing between these two lines but a new request.
            val resumed = stand.open()

            assertEquals(EsimWizardSteps.ACTIVATE, resumed.record.session.currentStepId)
            val code = resumed.esim?.activationCode
            assertNotNull(code, "resuming the activate step answered no profile, so the screen has no code to draw")
            assertEquals(activate.esim?.activationCode, code)
        }

    private companion object {
        const val SUBSCRIBER = "sub-1"
    }
}
