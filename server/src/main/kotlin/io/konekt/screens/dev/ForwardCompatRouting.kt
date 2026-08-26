package io.konekt.screens.dev

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: public, and deliberately. The screen carries no subscriber's data — two invented
// counters and a component nobody can render — and putting it behind a token would mean signing in to
// look at a demonstration of a rendering rule.
//
// It exists only where `DEV_SCREENS` is set, and `DevRoutesAreNotProductionTest` is what keeps that
// true: a development route that ships is a development route somebody finds.
fun Route.forwardCompatRoutes() {
    val json by inject<Json>()

    get<ForwardCompatScreenResource> {
        call.respondKompotComponent(json, ForwardCompatScreen.build())
    }
}
