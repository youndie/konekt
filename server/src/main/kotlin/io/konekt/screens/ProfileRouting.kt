package io.konekt.screens

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.http.subscriberId
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token. Every value on this screen is the caller's own, and which caller comes from
// the verified token rather than from anything they sent.
//
// ONE USE CASE AND ONE SCREEN, which is all a route here should be — `B-96`. It used to inject four
// repositories, pick a default tariff, resolve two ids against a catalogue and compose the sentence a
// subscriber reads about a pending change. None of that is about who is calling, and a routing file
// is the layer that knows who is calling.
fun Route.profileRoutes() {
    val viewProfile by inject<ViewProfileUseCase>()
    val json by inject<Json>()

    get<ProfileScreenResource> {
        call.respondKompotComponent(
            json,
            ProfileScreen.build(
                view = viewProfile(call.subscriberId()).getOrThrow(),
                nav = Shell.bottomNav(Shell.Tab.PROFILE),
            ),
        )
    }
}
