package io.konekt.feature.esim.shared.api

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.wizard.core.WizardTransition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

// The one action konekt adds to the wire, and it is the wizard's whole control surface.
//
// It is BOTH the action on a button and the body of the request that button makes: the client posts
// back the action it was given, unchanged. Nothing about a step's meaning is assembled on the client
// — which run this is and which way it moves are both facts the server wrote.
//
// It lives with this feature rather than in `:shared:components`, which holds the nine components
// the design canvas defines. This is one feature's verb, not part of that dictionary.
@Serializable
@SerialName("esim_wizard_step")
data class EsimWizardStepAction(
    val wizardId: String,
    // wizard-core's own type rather than a word of ours. It is already a wire type with a @SerialName
    // chosen for the purpose, and a second vocabulary for "next" would be two spellings of one idea
    // with nothing holding them together.
    val transition: WizardTransition,
) : KompotAction

// Actions are NOT generated. `@KompotComponentMarker` and the KSP processor cover components; the
// KompotAction hierarchy is registered by hand, the same way :kompot-wizard registers its three.
//
// Which means nothing fails at build time if this is left out of the application's Json. The action
// simply cannot be decoded — at runtime, on the one request that matters — and the wizard answers
// 400 for a body it built itself. `EsimWizardActionTest` is what refuses that.
val esimActionsSerializersModule =
    SerializersModule {
        polymorphic(KompotAction::class) {
            subclass(EsimWizardStepAction::class)
        }
    }
