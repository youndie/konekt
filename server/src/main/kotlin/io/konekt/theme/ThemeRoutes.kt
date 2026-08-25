package io.konekt.theme

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// The one endpoint a brand kit needs.
//
// **PUBLIC, and deliberately outside `authenticate`.** The first screen an operator's subscriber sees
// is the sign-in screen, and a sign-in screen painted in Material's default purple until a token
// exists is a rebrand that visibly fails at the only moment every user passes through. A brand kit
// carries no subscriber data; the palette is on the outside of the building already.
//
// **THE PATH IS A PARAMETER**, which is not a style choice. This repository's rule is that no endpoint
// path exists as a string outside a `*-shared-api` module — the client and the server name one
// constant, so they cannot drift — and a module for this one does not exist yet (see the handoff on
// B-22). Taking the path as an argument keeps this file honest until it does: the composition root
// passes the constant, and there is no second spelling of the path anywhere to go stale.
//
// Not covered here, and worth knowing before somebody reports it: there is no `ETag` and no
// `Cache-Control`. A kit is a handful of kilobytes fetched once per launch, and a cache header whose
// invalidation nobody has thought about is how an operator's deploy takes a week to reach a phone.
fun Route.themeRoutes(
    path: String,
    catalogue: BrandThemeCatalogue,
) {
    get(path) {
        // The bytes of the resource, verbatim. See BrandThemeCatalogue for why this server does not
        // decode a theme it does not need to understand.
        call.respondText(catalogue.document, ContentType.Application.Json)
    }
}
