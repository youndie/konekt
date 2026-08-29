package io.konekt.client.app

import io.konekt.feature.auth.shared.api.LOGIN_CODE_DEEPLINK
import io.konekt.feature.auth.shared.api.LOGIN_DEEPLINK
import io.konekt.feature.auth.shared.api.LoginCodeScreenResource
import io.konekt.feature.auth.shared.api.LoginCodeSubmit
import io.konekt.feature.auth.shared.api.LoginForms
import io.konekt.feature.auth.shared.api.LoginScreenResource
import io.konekt.feature.auth.shared.api.LoginSubmit
import io.konekt.feature.packages.shared.api.CustomPackageFields
import io.konekt.feature.purchase.shared.api.ORDER_DEEPLINK
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.TopUpForms
import io.konekt.feature.purchase.shared.api.TopUpScreenResource
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.serialization.serializer
import io.konekt.feature.packages.shared.api.CustomPackageForm as CustomPackageFormResource

// WHAT THE RUNNERS NEED BEFORE THE SERVER CAN BE ASKED.
//
// It was written inline in each runner, and the two had drifted: the desktop knew six deeplinks and
// the iOS one knew three, so the same `navigate` moved on one platform and printed "no handler" on
// the other. Nothing could notice — a route table built at a call site is not something a test can
// be handed.
//
// AND THE COPY IS GONE. It used to hold every destination, which was a second spelling of the graph
// the server publishes — `B-49`'s last criterion. What is left is a bootstrap, below, plus the two
// tables that are genuinely the CLIENT's: where a form posts, and where the application opens.
object KonektRoutes {
    // WHAT IS REACHABLE BEFORE THERE IS A SESSION, and that is all this map is since `B-49`.
    //
    // It used to be the whole route table, written by hand at each runner's call site, and it was a
    // second copy of the pairing the server publishes at `/api/v1/navigation` — the one place a
    // deployment could change its destinations without the client following. The graph is now what
    // resolves a deeplink; `KonektApp` fetches it at every session boundary and merges it over this.
    //
    // These two survive because the graph cannot answer yet. It sits behind the user tier — a public
    // one would promise destinations that all answer 401 — and the application opens on the login
    // screen with nothing to ask with. So the bootstrap is exactly the screens that ARE the way in,
    // which is a shorter list than "the ones we happened to write down" and stays that way: a screen
    // added here that is not part of signing in is a screen the graph should have named.
    val bootstrap: Map<String, String> =
        mapOf(
            // Matched by PREFIX: the server puts the number in the query, and a map keyed on the
            // whole string would need an entry per subscriber. `resolve` carries the tail across.
            LOGIN_CODE_DEEPLINK to addressOf<LoginCodeScreenResource>(),
            LOGIN_DEEPLINK to addressOf<LoginScreenResource>(),
            // AND ONE THE GRAPH CANNOT CARRY, which is a different reason from the two above and
            // worth keeping separate from them.
            //
            // `app://order/<id>` is parameterised. `kompot-tck` follows every route of a served
            // graph to its endpoint EXACTLY as written and substitutes nothing, so the prefix
            // answers 404 and so does the pattern — measured both ways. A graph carrying it is a
            // graph the conformance walk reports as broken, and a graph without it is a history
            // whose rows open nothing. Filed as `U15`; this entry goes when the kit can be handed a
            // value for a graph route, and the day it does the client stops knowing this address.
            ORDER_DEEPLINK to addressOf<OrderScreen>().substringBefore("/{"),
        )

    // WHERE EACH FORM POSTS. The toolkit's `submit_form` carries a form id and no address — routing
    // is deliberately the application's — so this is the client's half of that contract, and it lives
    // beside the route table because forgetting either produces the same symptom: a button that does
    // nothing.
    val submits: Map<String, String> =
        mapOf(
            LoginForms.NUMBER to addressOf<LoginSubmit>(),
            LoginForms.CODE to addressOf<LoginCodeSubmit>(),
            // The amount form posts to the screen's own address: the submit is a POST on the resource
            // that serves it, so nothing here spells a second path.
            TopUpForms.AMOUNT_FORM to addressOf<TopUpScreenResource>(),
            // The builder posts to its own address too, for the same reason the amount form does: a
            // sibling path named `submit` is one parameter away from colliding with something.
            CustomPackageFields.FORM_ID to addressOf<CustomPackageFormResource>(),
        )

    // Where the application opens, and where signing out returns to.
    val loginAddress: String get() = addressOf<LoginScreenResource>()

    val homeAddress: String get() = addressOf<HomeScreenResource>()
}

// Derived from the `@Resource` rather than typed again, so the ADDRESS is still spelled once — in the
// annotation, in the module both sides read.
internal inline fun <reified T : Any> addressOf(): String =
    ResourcesFormat()
        .encodeToPathPattern(serializer<T>())
        .let { if (it.startsWith("/")) it else "/$it" }
