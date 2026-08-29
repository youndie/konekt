package io.konekt.screens

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.konekt.http.subscriberId
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token. The screen shows one subscriber's money and one subscriber's allowance, and
// which subscriber comes from the verified token rather than from anything the caller sent.
//
// ONE USE CASE AND ONE SCREEN (`B-96`). It used to inject six things — four repositories and two card
// factories — and assemble the screen's eight arguments here, which is how a route came to know that
// a home screen has a brand name on it.
fun Route.homeRoutes() {
    val viewHome by inject<ViewHomeUseCase>()
    val screen by inject<HomeScreen>()
    val json by inject<Json>()

    get<HomeScreenResource> {
        // respondKompotComponent, never call.respond. A plain respond resolves the serialiser from
        // the concrete runtime class and drops the "type" discriminator on the ROOT of the tree —
        // nested children serialise perfectly, which is what makes it easy to miss — and the client
        // then receives an unknown component for the whole screen and, by design, draws nothing.
        // `CallRespondUsageTest` is what refuses the other spelling in the sources.
        call.respondKompotComponent(
            json,
            screen.build(
                view = viewHome(call.subscriberId()).getOrThrow(),
                nav = Shell.bottomNav(Shell.Tab.HOME),
            ),
        )
    }
}
