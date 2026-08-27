package io.konekt.screens

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.domain.KonektException
import io.konekt.feature.purchase.server.domain.PlanCatalog
import io.konekt.feature.purchase.shared.api.PlansScreenResource
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token. The catalogue itself carries nobody's data — but a subscriber reaches this
// screen from the home screen, and a public catalogue would be the one route in the product that
// answers without one. Keeping the tier uniform is worth more than the one request it saves.
fun Route.plansRoutes() {
    val plans by inject<PlanCatalog>()
    val json by inject<Json>()

    // ONE PLAN. A 404 for an id nobody sells rather than an empty screen: a plan can leave the
    // catalogue while a deeplink to it is still in somebody's hands.
    get<PlansScreenResource.ById> { params ->
        val plan = plans.find(params.planId) ?: throw KonektException.NotFound("plan")
        call.respondKompotComponent(json, PlanDetailScreen.build(plan, Shell.bottomNav(Shell.Tab.PLANS)))
    }

    get<PlansScreenResource> {
        // respondKompotComponent, never call.respond. A plain respond resolves the serialiser from the
        // concrete runtime class and drops the "type" discriminator on the ROOT of the tree, and the
        // client then receives an unknown component for the whole screen.
        call.respondKompotComponent(json, PlansScreen.build(plans.all(), Shell.bottomNav(Shell.Tab.PLANS)))
    }
}
