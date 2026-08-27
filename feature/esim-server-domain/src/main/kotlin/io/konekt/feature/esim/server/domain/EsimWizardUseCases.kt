package io.konekt.feature.esim.server.domain

import io.github.youndie.kompot.wizard.core.WizardTransition
import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching

class StartEsimWizardUseCase(
    private val sessions: EsimWizardSessions,
    private val ids: EsimIds,
) {
    suspend operator fun invoke(params: Params): Result<EsimWizardView> =
        suspendRunCatching {
            val record =
                EsimWizardRecord(
                    id = ids.next(),
                    subscriberId = params.subscriberId,
                    session = esimWizardEngine().start(EsimOrderDraft()),
                )

            // Persisted before it is answered. The run's id travels back inside the actions on the
            // screen, so a client that received one and could not find it afterwards would be holding
            // a button that does nothing.
            sessions.create(record)
            EsimWizardView(record)
        }

    data class Params(
        val subscriberId: String,
    )
}

// One step of one run.
//
// The order inside is the whole design and it is not the obvious one: the gate runs BEFORE the
// transition, not inside the resolver. wizard-core's engine models the graph and has no notion of
// refusal — a Next either moves or is the last step — so a rule that says "not from here, not yet"
// has to hold the session where it is rather than route it somewhere. That is what makes the
// slot-limit frame reachable at all.
class AdvanceEsimWizardUseCase(
    private val sessions: EsimWizardSessions,
    private val esims: EsimRepository,
    private val smDpPlus: SmDpPlus,
) {
    suspend operator fun invoke(params: Params): Result<EsimWizardView> =
        suspendRunCatching {
            val record = sessions.find(params.wizardId).ownedByOr404(params.subscriberId)

            // A finished run is read-only rather than an error. The client may still be holding its
            // last screen, and answering 404 to the button on it would replace a finished wizard with
            // a failure.
            if (record.session.isFinished) return@suspendRunCatching view(record)

            if (params.transition.movesForward()) {
                refusalFor(record)?.let { refusal ->
                    // Held exactly where it was: same step, same draft, nothing written. The screen
                    // that comes back is the one the subscriber is already looking at, with the
                    // reason on it.
                    return@suspendRunCatching view(record, refusal)
                }
            }

            var session = esimWizardEngine().transition(record.session, params.transition, record.session.draft)

            // ISSUING HAPPENS ON THE WAY IN, and exactly once.
            //
            // It is the only step of this flow that costs anything outside the process, and a client
            // can arrive here more than once — Back then Next, a retried request, a double tap. The
            // draft is what remembers, because it is the thing that is written in the same row as the
            // step; a flag anywhere else could be true while the session said otherwise.
            if (session.currentStepId == EsimWizardSteps.ACTIVATE && session.draft.issuedEsimId == null) {
                val issued = smDpPlus.issue(record.subscriberId)
                val esim = esims.create(record.subscriberId, issued.iccid, issued.activationCode)
                session = session.copy(draft = session.draft.copy(issuedEsimId = esim.id))
            }

            // Finishing on the last step is the subscriber saying the profile is installed. Only
            // then, and only if there is one: Finish may be sent from any step, and a run abandoned
            // before `activate` has nothing to mark.
            if (session.isFinished) {
                session.draft.issuedEsimId?.let { esims.markInstalled(it) }
            }

            val updated = record.copy(session = session)
            sessions.save(updated)
            view(updated)
        }

    private suspend fun view(
        record: EsimWizardRecord,
        refusal: EsimRefusal? = null,
    ): EsimWizardView =
        EsimWizardView(
            record = record,
            refusal = refusal,
            esim =
                record.session.draft.issuedEsimId
                    ?.let { esims.findById(it) },
        )

    private suspend fun refusalFor(record: EsimWizardRecord): EsimRefusal? =
        when (record.session.currentStepId) {
            EsimWizardSteps.CHECK -> {
                // The count is ours and the rule is the manager's. See SmDpPlus for why the split is
                // that way round.
                val capacity = smDpPlus.capacityFor(esims.countHeldBy(record.subscriberId))
                when (capacity) {
                    is SmDpPlus.Capacity.Available -> null
                    is SmDpPlus.Capacity.Refused -> EsimRefusal(capacity.code, capacity.text)
                }
            }

            else -> {
                null
            }
        }

    data class Params(
        val wizardId: String,
        val subscriberId: String,
        val transition: WizardTransition,
    )
}

// Back is never gated, and that is not an oversight to tidy up later. A subscriber who cannot go
// forward must still be able to go back; a refusal that blocked both would be a wizard with no exit
// except closing the application.
//
// A `when` with no `else` over a sealed interface, so a transition added upstream stops compiling
// here rather than silently defaulting to one answer or the other.
private fun WizardTransition.movesForward(): Boolean =
    when (this) {
        WizardTransition.Back -> false
        WizardTransition.Next -> true
        WizardTransition.Finish -> true
        is WizardTransition.JumpTo -> true
    }

// 404 AND NOT 403 for somebody else's run, the same rule every owner-scoped route follows. Repeated
// here rather than imported because the helper is Ktor's and this module has no business knowing
// about HTTP — and because the check belongs beside the owner, in the use case, not in the route.
private fun EsimWizardRecord?.ownedByOr404(subscriberId: String): EsimWizardRecord {
    if (this == null || this.subscriberId != subscriberId) {
        throw KonektException.NotFound("wizard")
    }
    return this
}

// OPENING THE INSTALL FLOW, which is different from starting one — and the difference is the whole
// reason this exists beside `StartEsimWizardUseCase`.
//
// Until now the only way in was `POST /api/v1/esim-wizard`, which creates a run every time it is
// called. Nothing in the product called it: no screen carried an action that led there, so a
// subscriber who bought a plan could not install it (`B-54`). Giving the flow an ADDRESS is what
// makes it reachable by an ordinary `navigate` like every other screen — and an address is fetched
// with a GET, which may be repeated.
//
// So this resumes. A subscriber who opens the screen, backgrounds the app and opens it again is in
// the same run, at the step they left; the alternative is a row per arrival and a draft that resets
// under them. The first arrival still creates one, which is a GET with a side effect exactly once
// per subscriber per install — worth knowing, and cheaper than the alternatives: a POST cannot be a
// `navigate` destination, and a screen that refused to exist until something POSTed would put the
// entry point back where nothing could reach it.
class OpenEsimWizardUseCase(
    private val sessions: EsimWizardSessions,
    private val ids: EsimIds,
) {
    suspend operator fun invoke(params: Params): Result<EsimWizardView> =
        suspendRunCatching {
            sessions.findUnfinishedBy(params.subscriberId)?.let { return@suspendRunCatching EsimWizardView(it) }

            val record =
                EsimWizardRecord(
                    id = ids.next(),
                    subscriberId = params.subscriberId,
                    session = esimWizardEngine().start(EsimOrderDraft()),
                )
            sessions.create(record)
            EsimWizardView(record)
        }

    data class Params(
        val subscriberId: String,
    )
}
