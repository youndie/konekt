package io.konekt.feature.esim.server.domain

import io.github.youndie.kompot.wizard.core.WizardEngine
import io.github.youndie.kompot.wizard.core.WizardStepResolver

// The graph, as a pure function of where you are.
//
// It branches on nothing today, and it is still worth being a resolver rather than a list: the moment
// a device that cannot scan needs a manual-entry step, the branch goes here — in a function a unit
// test can walk with no HTTP, no database and no UI — instead of into whichever route noticed first.
//
// `null` for the last step is wizard-core's own convention and it does NOT mean finished: a Next on
// `done` stays put, and the run ends only when the subscriber says so with Finish. That distinction
// is why the last screen can still be read, and re-read, before it is dismissed.
val esimWizardStepResolver: WizardStepResolver<EsimOrderDraft> =
    WizardStepResolver { currentStepId, _ ->
        when (currentStepId) {
            EsimWizardSteps.CHECK -> EsimWizardSteps.CONFIRM
            EsimWizardSteps.CONFIRM -> EsimWizardSteps.ACTIVATE
            EsimWizardSteps.ACTIVATE -> EsimWizardSteps.DONE
            else -> null
        }
    }

fun esimWizardEngine(): WizardEngine<EsimOrderDraft> =
    WizardEngine(
        initialStepId = EsimWizardSteps.CHECK,
        stepResolver = esimWizardStepResolver,
    )
