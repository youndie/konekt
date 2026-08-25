package io.konekt.feature.esim.server.data

import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.domain.KonektException
import io.konekt.feature.esim.server.domain.AdvanceEsimWizardUseCase
import io.konekt.feature.esim.server.domain.StartEsimWizardUseCase
import io.konekt.feature.esim.shared.api.EsimWizardResource
import io.konekt.feature.esim.shared.api.EsimWizardStepAction
import io.konekt.http.subscriberId
import io.ktor.server.request.receiveText
import io.ktor.server.resources.post
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token, both routes. A wizard run belongs to one subscriber, and the owner check is
// in the use case beside the principal rather than here — `authenticate` proves the caller is
// somebody and says nothing about whose run this is.
fun Route.esimWizardRoutes() {
    val startWizard by inject<StartEsimWizardUseCase>()
    val advanceWizard by inject<AdvanceEsimWizardUseCase>()
    val json by inject<Json>()

    post<EsimWizardResource> {
        val view = startWizard(StartEsimWizardUseCase.Params(subscriberId = call.subscriberId())).getOrThrow()

        // respondKompotComponent, never call.respond. A plain respond resolves the serialiser from
        // the concrete runtime class and drops the "type" discriminator on the ROOT of the tree, and
        // the client then receives an unknown component for the whole screen and draws nothing.
        call.respondKompotComponent(json, EsimWizardScreen.build(view))
    }

    post<EsimWizardResource.Step> {
        // The body is the ACTION the server put on the button, posted back unchanged. Read as a
        // KompotAction rather than as a request type of its own, because those are the same object:
        // inventing a DTO here would mean the client translating an action into a request, which is
        // the one place a wire contract can drift without anything failing to compile.
        val action =
            json.decodeKompotAction(call.receiveText()) as? EsimWizardStepAction
                ?: throw KonektException.Validation("action", "that is not a step of this wizard")

        val view =
            advanceWizard(
                AdvanceEsimWizardUseCase.Params(
                    wizardId = action.wizardId,
                    subscriberId = call.subscriberId(),
                    transition = action.transition,
                ),
            ).getOrThrow()

        call.respondKompotComponent(json, EsimWizardScreen.build(view))
    }
}
