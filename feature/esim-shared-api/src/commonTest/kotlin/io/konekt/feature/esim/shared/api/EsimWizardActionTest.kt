package io.konekt.feature.esim.shared.api

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.encodeKompotAction
import io.github.youndie.kompot.wizard.core.WizardTransition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

// The guard the component dictionary has, applied to the one action.
//
// It is needed for the same reason and a sharper one: a component's registration is generated, and
// this one is hand-written into `esimActionsSerializersModule`. Nothing refuses an application whose
// Json omits that module — the build is green, every screen renders, and the failure appears only
// when somebody presses Continue, as a 400 on a body the server itself wrote.
class EsimWizardActionTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule = esimActionsSerializersModule
        }

    private val actions: List<Pair<String, KompotAction>> =
        listOf(
            "esim_wizard_step" to EsimWizardStepAction("wiz-1", WizardTransition.Next),
            "esim_wizard_step" to EsimWizardStepAction("wiz-1", WizardTransition.Back),
            "esim_wizard_step" to EsimWizardStepAction("wiz-1", WizardTransition.Finish),
        )

    @Test
    fun `the action goes on the wire under its own name`() {
        actions.forEach { (wireName, action) ->
            val type =
                Json
                    .parseToJsonElement(json.encodeKompotAction(action))
                    .jsonObject["type"]
                    ?.jsonPrimitive
                    ?.content

            assertEquals(wireName, type)
        }
    }

    @Test
    fun `every transition survives the round trip`() {
        // Per transition rather than once, because the transition is a polymorphic type of its own
        // inside a polymorphic action: registering the outer one is not the same as the inner ones
        // decoding, and Back is the case a wizard uses least and needs most.
        actions.forEach { (_, action) ->
            assertEquals(action, json.decodeKompotAction(json.encodeKompotAction(action)))
        }
    }
}
