package io.konekt.screens

import io.github.youndie.kompot.navigation.NavigationGraph
import io.github.youndie.kompot.navigation.ScreenRoute
import io.github.youndie.kompot.navigation.ScreenRouteKind
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.components.BottomNavComponent
import io.konekt.components.BottomNavItem
import io.konekt.feature.auth.shared.api.LOGIN_CODE_DEEPLINK
import io.konekt.feature.auth.shared.api.LOGIN_DEEPLINK
import io.konekt.feature.auth.shared.api.LoginCodeScreenResource
import io.konekt.feature.auth.shared.api.LoginScreenResource
import io.konekt.feature.esim.shared.api.ESIM_INSTALL_DEEPLINK
import io.konekt.feature.esim.shared.api.EsimInstallScreenResource
import io.konekt.feature.packages.shared.api.CUSTOM_PACKAGE_DEEPLINK
import io.konekt.feature.purchase.shared.api.HistoryScreenResource
import io.konekt.feature.purchase.shared.api.ORDER_DEEPLINK
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.PlansScreenResource
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.konekt.feature.purchase.shared.api.TopUpScreenResource
import io.konekt.feature.roaming.shared.api.ROAMING_DEEPLINK
import io.konekt.feature.roaming.shared.api.RoamingScreenResource
import io.konekt.feature.shell.shared.api.HOME_DEEPLINK
import io.konekt.feature.shell.shared.api.ORDERS_DEEPLINK
import io.konekt.feature.shell.shared.api.PROFILE_DEEPLINK
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.feature.tariff.shared.api.TARIFFS_DEEPLINK
import io.konekt.feature.tariff.shared.api.TariffsScreenResource
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.konekt.openapi.resourceAddress
import io.konekt.feature.packages.shared.api.CustomPackageForm as CustomPackageFormResource

// THE APPLICATION'S SHELL: which destinations exist, and which of them are tabs.
//
// Two answers to two different questions, deliberately not one. The GRAPH is every destination a
// deeplink can name, and it is the toolkit's type — `kompot-navigation` has carried it since this
// build began and nothing used it. The TABS are a product decision about which four of those a
// subscriber sees at the bottom of the screen, and no toolkit has an opinion about that.
//
// Folding the second into the first was the tempting mistake: `ScreenRoute.kind` is a free string
// and "tab" would have fitted in it. It would also have been the nearest-looking word rather than
// the right one — the kit reads that field to decide what SHAPE a destination answers, and a
// conformance run would then have been told a tab is a body format.
object Shell {
    // Every destination, in one place, because a deeplink that resolves on one side and not the other
    // is a button that does nothing. The client used to hold its own copy of this map.
    //
    // ADDRESSES ARE ASKED OF THE `@Resource` CLASSES rather than written: a renamed segment moves the
    // routing tree and this graph together, and the alternative is a 404 in somebody's hands.
    fun graph(): NavigationGraph =
        NavigationGraph(
            routes =
                listOf(
                    ScreenRoute(
                        deeplink = HOME_DEEPLINK,
                        endpoint = resourceAddress<HomeScreenResource>(),
                        title = "Home",
                        // `screen`, even though this is the tree the realtime stream pushes into.
                        // The kit compares a route's kind against the kind the HTTP description
                        // declares for the same address and reports a disagreement — the route says
                        // what a client will PARSE, the description says what the server will SEND,
                        // and nothing else holds the two together. This endpoint answers one
                        // component tree; the updates arrive on a different address entirely.
                        kind = ScreenRouteKind.SCREEN,
                    ),
                    ScreenRoute(
                        deeplink = PLANS_DEEPLINK,
                        endpoint = resourceAddress<PlansScreenResource>(),
                        title = "Plans",
                        kind = ScreenRouteKind.SCREEN,
                    ),
                    ScreenRoute(
                        deeplink = ORDERS_DEEPLINK,
                        endpoint = resourceAddress<HistoryScreenResource>(),
                        title = "Orders",
                        kind = ScreenRouteKind.SCREEN,
                    ),
                    ScreenRoute(
                        deeplink = PROFILE_DEEPLINK,
                        endpoint = resourceAddress<ProfileScreenResource>(),
                        title = "Profile",
                        kind = ScreenRouteKind.SCREEN,
                    ),
                    // THE THREE THAT WERE MISSING, and their absence contradicted the sentence at
                    // the top of this list. The graph described six destinations while the client
                    // resolved nine — `top-up` and `esim-install` shipped with their screens and
                    // never joined it, and the order screen had no deeplink at all until an order row
                    // needed one. `B-49` exists to delete the client's copy in favour of this graph,
                    // and doing that today would have taken three screens out of the product.
                    //
                    // `EveryScreenIsReachableTest` walks this graph against a running deployment
                    // now, so the next omission fails rather than waiting to be noticed. (It used to
                    // name the test `B-49` deleted along with the client's copy.)
                    ScreenRoute(
                        deeplink = TOP_UP_DEEPLINK,
                        endpoint = resourceAddress<TopUpScreenResource>(),
                        title = "Top up",
                        // A FORM, not a screen: it answers a schema and a tree, and the kit compares
                        // this word against what the HTTP description says the same address sends.
                        kind = ScreenRouteKind.FORM,
                    ),
                    // TRAVEL PACKAGES, reached from the home screen where the cards themselves are.
                    // Not a tab: the canvas has four, and a subscriber who never leaves the country
                    // would carry a fifth for a feature they do not use.
                    ScreenRoute(
                        deeplink = ROAMING_DEEPLINK,
                        endpoint = resourceAddress<RoamingScreenResource>(),
                        title = "Travel packages",
                        kind = ScreenRouteKind.SCREEN,
                    ),
                    // THE CUSTOM PACKAGE BUILDER, reached from the plans tab. A `form` and not a
                    // `screen`: it answers a schema and a tree, and the conformance kit compares this
                    // word against what the HTTP description says the same address sends.
                    ScreenRoute(
                        deeplink = CUSTOM_PACKAGE_DEEPLINK,
                        endpoint = resourceAddress<CustomPackageFormResource>(),
                        title = "Build your own",
                        kind = ScreenRouteKind.FORM,
                    ),
                    // THE TARIFF CATALOGUE, reached from the profile. Not a tab: the canvas has four
                    // and a fifth would be a change to the shell for a screen a subscriber opens
                    // rarely — what they are on belongs beside their number, and changing it belongs
                    // one press further in.
                    ScreenRoute(
                        deeplink = TARIFFS_DEEPLINK,
                        endpoint = resourceAddress<TariffsScreenResource>(),
                        title = "Your tariff",
                        kind = ScreenRouteKind.SCREEN,
                    ),
                    ScreenRoute(
                        deeplink = ESIM_INSTALL_DEEPLINK,
                        endpoint = resourceAddress<EsimInstallScreenResource>(),
                        title = "Install eSIM",
                        kind = ScreenRouteKind.SCREEN,
                    ),
                    // ONE ORDER IS DELIBERATELY NOT HERE, and the conformance kit is why.
                    //
                    // `app://order/<id>` is the first parameterised destination this product has, and
                    // the kit FOLLOWS every route of this graph to the screen behind it — with the
                    // endpoint exactly as written. The prefix `/api/v1/screens/orders` is not a route
                    // and answered 404; the pattern `/api/v1/screens/orders/{orderId}` is not an
                    // address and answered 404 as well, because nothing substitutes an id for a graph
                    // route the way `TckConfig.pathParameters` does for an endpoint.
                    //
                    // So this graph is what it can be checked as: destinations that ANSWER as written.
                    // The client resolves one more than that, and
                    // `EveryScreenIsReachableTest` declares which one and why rather than filtering
                    // by shape — a filter would silently absorb the next omission.
                    //
                    // It is worth an upstream ask (a graph route with a placeholder is not an exotic
                    // thing) and it is not worth a route that 404s in the meantime.
                    // THE WAY IN, and it is in the graph even though no tab points at it. A graph
                    // that only described the tabs would leave the client resolving two of its
                    // transitions from a map and two from a service, which is the arrangement this
                    // replaces.
                    ScreenRoute(
                        deeplink = LOGIN_DEEPLINK,
                        endpoint = resourceAddress<LoginScreenResource>(),
                        title = "Sign in",
                        kind = ScreenRouteKind.FORM,
                    ),
                    ScreenRoute(
                        deeplink = LOGIN_CODE_DEEPLINK,
                        endpoint = resourceAddress<LoginCodeScreenResource>(),
                        title = "Your code",
                        kind = ScreenRouteKind.FORM,
                    ),
                ),
        )

    // The bar, built for the screen that is about to carry it.
    //
    // `selected` is decided HERE because the server is what knows which screen it is building. A
    // client deciding it by comparing its address against an action's payload would be a second
    // opinion about which tab is open, and the two would disagree the first time an address gained a
    // query parameter — which the login step already has.
    fun bottomNav(selected: Tab): BottomNavComponent =
        BottomNavComponent(
            id = "shell-nav",
            items =
                Tab.entries.map { tab ->
                    BottomNavItem(
                        label = tab.label,
                        action = NavigateAction(tab.deeplink),
                        selected = tab == selected,
                    )
                },
        )

    // The four tabs, in the order they are drawn.
    //
    // FOUR AND NOT THREE, and the canvas is why the question comes up at all: section 01 draws four
    // and section 05 draws three, dropping Orders. Four is taken because Orders exists and is
    // otherwise unreachable — a screen this product builds, tests and cannot show anybody.
    enum class Tab(
        val label: String,
        val deeplink: String,
    ) {
        HOME("Home", HOME_DEEPLINK),
        PLANS("Plans", PLANS_DEEPLINK),
        ORDERS("Orders", ORDERS_DEEPLINK),
        PROFILE("Profile", PROFILE_DEEPLINK),
    }
}
