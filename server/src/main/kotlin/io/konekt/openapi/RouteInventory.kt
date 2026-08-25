package io.konekt.openapi

import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.OptionalParameterRouteSelector
import io.ktor.server.routing.ParameterRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.TrailingSlashRouteSelector
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path

// A query parameter of an endpoint, as the routing tree knows it. `ktor-server-resources` turns a
// property of a `@Resource` class into one of two selectors — required or optional — so this is read
// out of the tree rather than written down beside the path.
data class QueryParameter(
    val name: String,
    val required: Boolean,
)

// One endpoint of the running server, as Ktor built it.
//
// EVERY FIELD HERE IS DERIVED, and that is the whole point of the file. The path comes from the
// `@Resource` classes through Ktor's own OpenAPI path format, the method from the selector the route
// builder created, and `secured` from an `authenticate { }` somewhere above the node. A document
// that declared any of the three beside the endpoint would be a second opinion about the routing,
// and the two would disagree exactly when it mattered.
data class RouteEntry(
    val method: String,
    val path: String,
    val secured: Boolean,
    val queryParameters: List<QueryParameter>,
) {
    // Method and path together, because one path carries a GET and a POST with entirely different
    // contracts. The same key `kompot-tck` uses when it records what a walk reached.
    val key: String get() = "$method $path"

    val pathParameters: List<String>
        get() = PLACEHOLDER.findAll(path).map { it.groupValues[1] }.toList()

    private companion object {
        // `{orderId}` — one placeholder of an OpenAPI path template, spelled the way the kit spells
        // it.
        val PLACEHOLDER = Regex("""\{([^}]+)}""")
    }
}

// Every endpoint with a handler under this node, sorted so two runs produce the same document.
fun routeInventory(root: RoutingNode): List<RouteEntry> = root.getAllRoutes().map { it.toEntry() }.sortedBy { it.key }

private fun RoutingNode.toEntry(): RouteEntry {
    // Root last. The order does not matter to any answer below, but a stable one makes a failure
    // message readable.
    val lineage = generateSequence(this) { it.parent }.toList()

    // THE UNKNOWN SELECTOR IS A FAILURE AND NOT A SHRUG. Ktor's `path()` silently skips any selector
    // that is not a path component, which is right for a diagnostic string and wrong for a contract:
    // a header-matched or host-matched route would appear here as a second endpoint on somebody
    // else's path, and nothing would say so. If a route of this build ever grows one, this stops the
    // build rather than describing it wrongly.
    val unknown = lineage.map { it.selector }.filterNot(::isDescribable)
    require(unknown.isEmpty()) {
        "the route at ${path(OpenApiRoutePathFormat)} is built from a selector this generator cannot " +
            "describe: ${unknown.joinToString { it::class.simpleName ?: it.toString() }}. Teach " +
            "RouteInventory about it, or the document will be wrong about that route."
    }

    val methods = lineage.mapNotNull { (it.selector as? HttpMethodRouteSelector)?.method?.value }
    require(methods.size == 1) {
        "the route at ${path(OpenApiRoutePathFormat)} resolves to ${methods.size} HTTP methods ($methods); " +
            "an endpoint is one method and one path"
    }

    return RouteEntry(
        method = methods.single(),
        path = path(OpenApiRoutePathFormat),
        secured = lineage.any { it.selector is AuthenticationRouteSelector },
        queryParameters =
            lineage
                .reversed()
                .mapNotNull { node ->
                    when (val selector = node.selector) {
                        is ParameterRouteSelector -> QueryParameter(selector.name, required = true)
                        is OptionalParameterRouteSelector -> QueryParameter(selector.name, required = false)
                        else -> null
                    }
                },
    )
}

private fun isDescribable(selector: RouteSelector): Boolean =
    selector is RootRouteSelector ||
        selector is PathSegmentConstantRouteSelector ||
        selector is PathSegmentParameterRouteSelector ||
        selector === TrailingSlashRouteSelector ||
        selector is HttpMethodRouteSelector ||
        selector is ParameterRouteSelector ||
        selector is OptionalParameterRouteSelector ||
        selector is AuthenticationRouteSelector
