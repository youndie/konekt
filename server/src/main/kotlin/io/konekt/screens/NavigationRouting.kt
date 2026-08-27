package io.konekt.screens

import io.github.youndie.kompot.navigation.NavigationGraph
import io.konekt.feature.shell.shared.api.NavigationResource
import io.ktor.http.ContentType
import io.ktor.server.resources.get
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token, like every screen this graph points at. The graph names addresses and
// nothing else — no subscriber's data is in it — but a public graph would be the one route in the
// product answering without a session, and uniformity is worth more than the request it saves. It is
// also the honest tier: everything it describes needs a token, so a graph that did not would promise
// a client four destinations it could not reach.
//
// THE BODY IS THE TOOLKIT'S TYPE AT THE ROOT, not wrapped in an envelope of ours. A conformance run
// reads `routes` off the top level, and anything else makes the check return before it has looked at
// a single route — a check that passes because it found nothing, which is the failure this
// repository's whole conformance gate exists to make impossible.
fun Route.navigationRoutes() {
    val json by inject<Json>()

    get<NavigationResource> {
        call.respondText(
            json.encodeToString(NavigationGraph.serializer(), Shell.graph()),
            ContentType.Application.Json,
        )
    }
}
