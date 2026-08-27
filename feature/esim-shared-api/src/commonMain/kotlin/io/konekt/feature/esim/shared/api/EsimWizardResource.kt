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

// THE INSTALL FLOW AS A PLACE, which is what it was missing.
//
// `EsimWizardResource` above is a POST that creates a run and answers its screen. That shape can be
// called and cannot be NAVIGATED TO — the client fetches a screen with a GET — so nothing in any
// served tree could point at it, and the whole feature was unreachable from the product (`B-54`).
//
// A GET on this address opens the subscriber's run: theirs if one is unfinished, a new one if not.
// The POST stays, because a client that wants a fresh run should not have to finish an old one first.
@Resource("/api/v1/screens/esim-install")
class EsimInstallScreenResource

const val ESIM_INSTALL_DEEPLINK: String = "app://esim-install"
