package io.konekt.client.app

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.encodeKompotAction
import io.github.youndie.kompot.wizard.core.WizardTransition
import io.konekt.feature.esim.shared.api.ESIM_INSTALL_DEEPLINK
import io.konekt.feature.esim.shared.api.EsimInstallScreenResource
import io.konekt.feature.esim.shared.api.EsimWizardResource
import io.konekt.feature.esim.shared.api.EsimWizardStepAction
import io.ktor.client.HttpClient
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// STEPPING THE WIZARD, and until now nothing did.
//
// `B-54` gave the install flow an address and the screen drew — which is what its acceptance asked
// for and is not the same claim as the flow working. Every control on it sent an
// `EsimWizardStepAction`, the action decoded (`ClientDecodesEveryActionTest` says so), reached the
// handler chain, matched nothing, and the runner printed "no handler". Four buttons, none of them
// doing anything, behind a door that had just been built for them.
//
// The shape is `BuyPlan`'s and for the same reason: a holder that knew what an eSIM wizard was would
// be this application's holder rather than a reusable one. What differs is the answer — a purchase
// creates something and the destination depends on WHAT was created, while a wizard step moves a run
// that already has an address. So this posts and answers with that address, unchanged.
//
// THE SAME ADDRESS FOR EVERY STEP THAT MOVES THE RUN, which is what makes it work rather than a
// shortcut: the run is persisted and `/api/v1/screens/esim-install` OPENS it — resuming rather than
// starting — so re-fetching after a step shows the step it moved to. `KonektApp` refetches when an
// action answers with the address it is already on, which is the path confirming a purchase takes.
//
// AND ONE TRANSITION THAT DOES NOT MOVE THE RUN BUT ENDS IT. `Finish` is the exception, and treating
// it like the others is `B-76`: see the branch below.
class EsimInstall(
    private val http: HttpClient,
    private val json: Json,
) {
    suspend fun destinationFor(action: KompotAction): Destination? {
        if (action !is EsimWizardStepAction) return null

        // The action posted back UNCHANGED, which is the contract the server states on its side: the
        // body of a step is the action the server put on the button. Encoding it through the
        // application's own `Json` is what keeps the polymorphic scope the same on both ends —
        // building a request type of its own here is the one place a wire contract can drift without
        // anything failing to compile.
        val response =
            http.post(EsimWizardResource.Step()) {
                contentType(ContentType.Application.Json)
                // `encodeKompotAction` AND NOT `EsimWizardStepAction.serializer()`, which is the
                // first thing this did and answered 422 on every press.
                //
                // A concrete serializer writes the fields and NOT the `type` discriminator — that is
                // the polymorphic base's job — so the body arrived without one, `decodeKompotAction`
                // answered an `UnknownAction`, and the route refused it as "not a step of this
                // wizard". Correctly: from where the server stands, it was not one.
                //
                // This repository already carries the same trap from the other side: `call.respond`
                // of a `KompotComponent` drops the discriminator on the ROOT while every nested child
                // serialises perfectly, which is why `respondKompotComponent` exists. Encoding an
                // action is the mirror, and the toolkit ships the mirror function.
                setBody(json.encodeKompotAction(action))
            }

        // THE ANSWER IS CHECKED, and the first version of this did not check it. Ktor's client does
        // not throw on a 4xx unless asked to, so a refused step returned the screen's address, the
        // holder refetched, and the wizard came back on the SAME step — a button that looks pressed
        // and does nothing, which is the exact defect this class exists to fix, reproduced one layer
        // in. It cost a debugging session because "the press did nothing" is what both look like.
        check(response.status.isSuccess()) {
            "the wizard refused a step: ${response.status}"
        }

        // FINISH IS THE ONE TRANSITION THAT LEAVES, and answering the wizard's own address for it is
        // what made `Done` start the flow again.
        //
        // The refetch below is right for every step that MOVES the run: the address is stable, the
        // server answers wherever the run now is, and one shape covers all of them. `Finish` ends the
        // run — and a `GET` of this address with no unfinished run correctly STARTS one, which is
        // deliberate and documented in `OpenEsimWizardUseCase` for the subscriber who comes back to
        // install a second line. So a subscriber who pressed `Done` was shown step one of a new
        // wizard, and each press wrote another session row (`B-76`).
        //
        // `endOfFlow` and not `next`: the wizard is pushed on top of wherever it was opened from, so
        // replacing only the top would leave the order and the catalogue underneath and put a back
        // control on the home screen — the first defect this application was reported for. And not
        // `startOver`, which would refetch the navigation graph because an eSIM was installed.
        return when (action.transition) {
            WizardTransition.Finish -> Destination.endOfFlow(KonektRoutes.homeAddress)
            else -> Destination.next(installAddress)
        }
    }

    // Where every step that moves the run lands. NOT where `Finish` lands: a `GET` here with no
    // unfinished run starts a new one — right for a subscriber who comes back to install a second
    // line, wrong for one who has just pressed `Done`.
    private val installAddress: String
        get() =
            ResourcesFormat()
                .encodeToPathPattern(serializer<EsimInstallScreenResource>())
                .let { if (it.startsWith("/")) it else "/$it" }

    private companion object {
        // Named so the deeplink stays spelled once even though this class does not use it: the
        // address above and `KonektRoutes`'s entry for `ESIM_INSTALL_DEEPLINK` must resolve to the
        // same screen, and a reader checking that should find both names in one place.
        val DEEPLINK = ESIM_INSTALL_DEEPLINK
    }
}
