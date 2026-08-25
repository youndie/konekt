package io.konekt.feature.esim.shared.api

import io.ktor.resources.Resource

// Two verbs on one flow: begin a run, and move an existing one along.
//
// The run's identifier is NOT in the path of the second. It travels inside the action the client
// posts, because that action is the thing the server handed it — a button on a step screen already
// carries which wizard it belongs to, and putting the id in the path as well would be the same fact
// written twice with nothing holding the two spellings together.
@Resource("/api/v1/esim-wizard")
class EsimWizardResource {
    @Resource("step")
    class Step(
        val parent: EsimWizardResource = EsimWizardResource(),
    )
}
