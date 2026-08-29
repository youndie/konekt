package io.konekt.roaming

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.feature.roaming.shared.api.RoamingScreenResource
import io.konekt.http.subscriberId
import io.konekt.screens.Shell
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token. Every package on this screen is the caller's own, and which caller comes
// from the verified token rather than from anything they sent — which is the half the deleted
// development route got wrong: it took `subscriberId` from the query string.
fun Route.roamingRoutes() {
    val viewRoaming by inject<ViewRoamingUseCase>()
    val screen by inject<RoamingScreen>()
    val json by inject<Json>()

    get<RoamingScreenResource> {
        // `respondKompotComponent`, never `call.respond`: a plain respond resolves the serialiser from
        // the concrete runtime class and drops the "type" discriminator on the ROOT of the tree, and
        // the client then receives an unknown component for the whole screen.
        call.respondKompotComponent(
            json,
            screen.build(viewRoaming(call.subscriberId()).getOrThrow(), Shell.bottomNav(Shell.Tab.HOME)),
        )
    }
}
