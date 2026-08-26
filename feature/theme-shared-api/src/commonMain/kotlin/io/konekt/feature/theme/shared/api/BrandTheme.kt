package io.konekt.feature.theme.shared.api

// The brand kit's address, in one place because it cannot be a `@Resource` either — for a different
// reason from `RealtimeStream`'s, and worth stating so the next reader does not assume symmetry.
//
// It COULD be a typed resource on the server. It cannot be one usefully on the client: the theme is
// fetched before anything else, by the composition root, which holds a plain URL and no
// `ktor-client-resources` typing to route through. Both sides therefore name this constant, which is
// what the repository's rule is actually for — the path exists once, and a rename is a compile error
// rather than a 404 in somebody's hands.
//
// A module of its own rather than a corner of somebody else's, because the theme belongs to no
// feature. It is what every screen is drawn in.
object BrandTheme {
    const val PATH = "/api/v1/theme"

    // The environment variable that decides WHICH kit is served. Here rather than in the server's
    // config because it is half of a contract: an operator reading the deployment material and a
    // developer reading the route should find one spelling.
    const val BRAND_ENV = "BRAND"

    // The kit served when nothing chose. An operator who has not decided still gets a coherent
    // application rather than a startup failure — and `brand-a` is the one the design canvas is drawn
    // in, so an unconfigured deployment looks like the product rather than like a default.
    const val DEFAULT_BRAND = "brand-a"
}
