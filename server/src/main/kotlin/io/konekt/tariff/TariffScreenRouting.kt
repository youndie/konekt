package io.konekt.tariff

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.feature.tariff.shared.api.TariffChangeScreenResource
import io.konekt.feature.tariff.shared.api.TariffsScreenResource
import io.konekt.http.subscriberId
import io.konekt.screens.Shell
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token, both. The catalogue itself carries nobody's data, and the answer does — the
// current tariff and a pending change are this subscriber's. The change screen's owner check lives in
// the use case beside the subscriber id, where `ViewTariffChangeUseCase` puts it.
//
// SEPARATE FROM `tariffRoutes()`, which answers DTOs and is what the end-to-end suite drives. Two
// route functions on one feature because they answer different things: one is the contract and the
// other is a screen, and folding a screen into the DTO routes is how a route ends up choosing its
// representation from an `Accept` header.
fun Route.tariffScreenRoutes() {
    val viewTariffs by inject<ViewTariffsUseCase>()
    val viewChange by inject<ViewTariffChangeUseCase>()
    val json by inject<Json>()

    get<TariffsScreenResource> {
        call.respondKompotComponent(
            json,
            TariffsScreen.build(
                view = viewTariffs(call.subscriberId()).getOrThrow(),
                nav = Shell.bottomNav(Shell.Tab.PROFILE),
            ),
        )
    }

    // ONE CHANGE. `respondKompotComponent`, never `call.respond`: a plain respond resolves the
    // serialiser from the concrete runtime class and drops the "type" discriminator on the ROOT, and
    // the client then receives an unknown component for the whole screen.
    get<TariffChangeScreenResource> { params ->
        val view =
            viewChange(
                ViewTariffChangeUseCase.Params(changeId = params.changeId, subscriberId = call.subscriberId()),
            ).getOrThrow()
        call.respondKompotComponent(
            json,
            TariffChangeScreen.build(view, Shell.bottomNav(Shell.Tab.PROFILE)),
        )
    }
}
