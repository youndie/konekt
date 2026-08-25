package io.konekt.feature.esim.server.domain

import io.github.youndie.kompot.wizard.core.WizardSession
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// One eSIM profile the subscriber holds.
//
// The activation code is nullable because a profile exists before a code does: `ordered` is a real
// state and the canvas draws it. What is never nullable is the ICCID — a profile without one is not
// a profile.
data class EsimProfile(
    val id: String,
    val subscriberId: String,
    val iccid: String,
    val status: String,
    val activationCode: String?,
    val createdAt: Instant,
    val activatedAt: Instant? = null,
)

// The four steps, in the order they are lived.
//
// They are the canvas's frames rather than a decomposition of the work: `activate` and `done` both
// show the same code, and that is deliberate rather than redundant — somebody whose camera failed on
// the QR still needs the string, and a flow that takes the code away at the last step is a flow that
// hides the one thing worth keeping.
object EsimWizardSteps {
    const val CHECK = "check"
    const val CONFIRM = "confirm"
    const val ACTIVATE = "activate"
    const val DONE = "done"

    val order: List<String> = listOf(CHECK, CONFIRM, ACTIVATE, DONE)

    val total: Int = order.size

    // One-based, the way a step meter says it out loud. An unknown id answers 1 rather than 0 or a
    // throw: a meter is chrome, and chrome that crashes a screen is worse than chrome that is wrong.
    fun indexOf(stepId: String): Int = (order.indexOf(stepId) + 1).coerceAtLeast(1)
}

// What the run has accumulated. One field, and it is an idempotency key rather than an input.
//
// A profile is issued on the way into `activate`, and a client may arrive there twice — a Back
// followed by a Next, a retried request, a redelivered tap. Issuing is the one step of this flow that
// costs something outside the process, so the draft records that it happened and the second arrival
// finds it done.
@Serializable
data class EsimOrderDraft(
    val issuedEsimId: String? = null,
)

// One wizard run as it is stored: who it belongs to, and the engine's own immutable session value.
data class EsimWizardRecord(
    val id: String,
    val subscriberId: String,
    val session: WizardSession<EsimOrderDraft>,
)

// What a step request answers with: the run, the profile if there is one yet, and the refusal if the
// run was held where it was.
//
// THE REFUSAL IS NOT AN EXCEPTION, and that is the decision this type exists to carry. A slot limit
// answered with a 409 is a status code with no screen behind it: the client has nothing to draw, the
// wizard is neither here nor there, and the subscriber is told "conflict". Held in the view, the same
// step comes back with a banner on it — which is precisely what the canvas draws.
data class EsimWizardView(
    val record: EsimWizardRecord,
    val refusal: EsimRefusal? = null,
    val esim: EsimProfile? = null,
)

// Why a step would not advance, in the two halves a refusal has: a word the client may branch on and
// a sentence a person reads.
data class EsimRefusal(
    val code: String,
    val text: String,
)
