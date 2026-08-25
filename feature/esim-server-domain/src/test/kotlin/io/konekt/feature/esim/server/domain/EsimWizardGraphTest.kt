package io.konekt.feature.esim.server.domain

import io.github.youndie.kompot.wizard.core.WizardTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The graph on its own: no HTTP, no database, no mock of anything. This is the reason the flow is
// built on a step machine at all — the branching is a pure function, so it is checkable here rather
// than through four requests.
class EsimWizardGraphTest {
    private val engine = esimWizardEngine()

    private fun walk(vararg transitions: WizardTransition) =
        transitions.fold(engine.start(EsimOrderDraft())) { session, transition ->
            engine.transition(session, transition, session.draft)
        }

    @Test
    fun `the four steps run in the order the canvas draws them`() {
        assertEquals(EsimWizardSteps.CHECK, engine.start(EsimOrderDraft()).currentStepId)
        assertEquals(EsimWizardSteps.CONFIRM, walk(WizardTransition.Next).currentStepId)
        assertEquals(EsimWizardSteps.ACTIVATE, walk(WizardTransition.Next, WizardTransition.Next).currentStepId)
        assertEquals(
            EsimWizardSteps.DONE,
            walk(WizardTransition.Next, WizardTransition.Next, WizardTransition.Next).currentStepId,
        )
    }

    @Test
    fun `a next on the last step stays put and does not finish the run`() {
        // wizard-core's own distinction, and it is the one that keeps the last screen readable: the
        // resolver answering null means "no step after this", not "the wizard is over". A run ends
        // when the subscriber says Finish.
        val end =
            walk(
                WizardTransition.Next,
                WizardTransition.Next,
                WizardTransition.Next,
                WizardTransition.Next,
            )

        assertEquals(EsimWizardSteps.DONE, end.currentStepId)
        assertFalse(end.isFinished, "a Next on the last step finished the run")
    }

    @Test
    fun `back leads where the subscriber came from`() {
        val back = walk(WizardTransition.Next, WizardTransition.Next, WizardTransition.Back)

        assertEquals(EsimWizardSteps.CONFIRM, back.currentStepId)
        assertEquals(listOf(EsimWizardSteps.CHECK), back.history)
    }

    @Test
    fun `back on the first step stays on the first step`() {
        val start = walk(WizardTransition.Back)

        assertEquals(EsimWizardSteps.CHECK, start.currentStepId)
        assertTrue(start.history.isEmpty())
    }

    @Test
    fun `finish ends the run from wherever it is`() {
        assertTrue(walk(WizardTransition.Finish).isFinished)
    }

    @Test
    fun `the step meter counts from one and never reads zero`() {
        assertEquals(1, EsimWizardSteps.indexOf(EsimWizardSteps.CHECK))
        assertEquals(4, EsimWizardSteps.indexOf(EsimWizardSteps.DONE))
        assertEquals(4, EsimWizardSteps.total)
        // Chrome that crashes a screen is worse than chrome that is wrong, so an id from a build that
        // knew more steps than this one answers 1 rather than 0 or an exception.
        assertEquals(1, EsimWizardSteps.indexOf("a_step_from_a_later_build"))
    }
}
