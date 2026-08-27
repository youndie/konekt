package io.konekt.feature.shell.shared.api

import io.ktor.resources.Resource

// THE SHELL, and it is a feature module without a server half because it has no domain of its own.
//
// What lives here is what a client needs in order to be an application rather than a screen: where
// the destinations are, and what the account behind the session is. Neither belongs to purchase,
// usage or auth — a bar naming all four of them would make whichever feature owned it the one every
// other feature reaches into.

// THE ROUTE GRAPH. Answers `kompot-navigation`'s `NavigationGraph`: deeplink, endpoint, title and
// kind, for every destination this deployment serves.
//
// It exists so that the mapping from a deeplink to an address is served rather than compiled in. The
// client used to carry a `Map<deeplink, address>` written by hand beside the one the server builds
// its `navigate` actions from — two spellings of one contract, and the kind that goes wrong quietly:
// a renamed address leaves a button that does nothing rather than a build that fails.
@Resource("/api/v1/navigation")
class NavigationResource

// The account as its owner sees it: the number, what is installed on it, and the way out. Not the
// canvas's full profile — payment methods, auto top-up and language name features this product does
// not have, and a row that draws one is a mockup rather than a product.
@Resource("/api/v1/screens/profile")
class ProfileScreenResource

// WHERE THE TABS GO. Spelled once, here, and read by both sides: the server builds the actions, the
// client resolves them through the graph. `PLANS_DEEPLINK` is not among them — it belongs to the
// purchase feature and is already spelled there, which is the rule rather than an exception.
const val HOME_DEEPLINK: String = "app://home"
const val ORDERS_DEEPLINK: String = "app://orders"
const val PROFILE_DEEPLINK: String = "app://profile"
