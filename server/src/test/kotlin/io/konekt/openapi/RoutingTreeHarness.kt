package io.konekt.openapi

import io.konekt.RouteGroup
import io.konekt.baseModule
import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.auth.server.data.configureAuthentication
import io.konekt.mountKonektRoutes
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

// THE ROUTING TREE OF A ROUTE TABLE, WITHOUT A DATABASE — shared, because two guards now ask the same
// question of it and a second copy of this is a second opinion about what a deployment mounts.
//
// The application it assembles is the composition root MINUS the database, the feature bindings and
// the workers. That subtraction is what makes it runnable at all — `Application.module` opens a
// connection pool in its first line — and it is safe for this purpose because `by inject<T>()` is
// lazy: a route registers its handler without resolving anything, and nothing here ever serves a
// request. What it is NOT safe for is anything else, which is why it lives in the test source set
// rather than beside the production module.
internal suspend fun ApplicationTestBuilder.routingTreeOf(groups: List<RouteGroup>): RoutingNode {
    var captured: RoutingNode? = null
    application {
        baseModule()
        // The provider has to exist before `authenticate(AUTH_JWT)` is mounted: the route-scoped
        // plugin looks its providers up on install and fails naming the configuration. The secret
        // signs nothing here — no token is ever minted or read.
        configureAuthentication(DESCRIBING_JWT)
        routing { mountKonektRoutes(groups) }
        captured = routingRoot
    }
    startApplication()
    return captured ?: error("the application module never ran")
}

// Every endpoint a table mounts, as Ktor built it: method, path and whether an `authenticate { }`
// sits above it. Derived from the tree and not from a list written down beside it.
internal fun inventoryOf(groups: List<RouteGroup>): List<RouteEntry> {
    var inventory: List<RouteEntry>? = null
    testApplication { inventory = routeInventory(routingTreeOf(groups)) }
    return inventory ?: error("the application never assembled")
}

private val DESCRIBING_JWT = JwtConfig(secret = "route-table-harness", issuer = "konekt", audience = "konekt-app")

// `"GET /api/v1/plans"` per endpoint — the form a difference between two tables is readable in.
internal fun servedBy(groups: List<RouteGroup>): Set<String> = inventoryOf(groups).map { it.key }.toSet()
