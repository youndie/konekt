package io.konekt.screens.dev

import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.routing.Route

// A ROUTE THAT THROWS, AND IT EXISTS BECAUSE NOTHING ELSE DOES.
//
// B-26's first acceptance criterion asks for "a katcher report if the route throws", and until this
// file the strongest thing that could be asserted was that katcher's ingest address answers rather
// than 404s. That is a check on a URL, not on a pipeline — and the pipeline turned out to be broken
// in a way no amount of address-checking could show: `Katcher.start` installs an uncaught-exception
// handler, `StatusPages` catches every route exception before one could run, and so a correctly
// configured katcher was structurally unable to receive anything the server did.
//
// A deliberate failure is the only way to find that out, which is why the item named this route in
// the shape of `PAYMENT_MOCK_MODE`: an operator's switch, not a bug left in on purpose.
//
// AUTH TIER: public, and mounted only where `DEV_SCREENS` is set — the same gate as every other
// demonstration control, with `DevRoutesAreNotProductionTest` keeping it out of a real build. A route
// that reliably returns 500 is a denial-of-service primitive if it ships, and it carries no
// subscriber's data, so a token would buy nothing and cost a sign-in to demonstrate with.
@Resource("/api/v1/dev/fail")
class FailingResource

fun Route.failingRoutes() {
    get<FailingResource> {
        // A plain exception rather than a `KonektException`: the domain's refusals are ANSWERS, mapped
        // to 4xx and never reported, and one of them here would prove the opposite of what this route
        // is for. What is wanted is the shape of a defect — something nobody planned for.
        error("deliberate failure from the development route, for a crash report to exist at all")
    }
}
