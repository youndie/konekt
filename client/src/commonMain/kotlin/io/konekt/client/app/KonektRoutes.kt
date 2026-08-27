package io.konekt.client.app

import io.konekt.feature.auth.shared.api.LOGIN_CODE_DEEPLINK
import io.konekt.feature.auth.shared.api.LOGIN_DEEPLINK
import io.konekt.feature.auth.shared.api.LoginCodeScreenResource
import io.konekt.feature.auth.shared.api.LoginCodeSubmit
import io.konekt.feature.auth.shared.api.LoginForms
import io.konekt.feature.auth.shared.api.LoginScreenResource
import io.konekt.feature.auth.shared.api.LoginSubmit
import io.konekt.feature.esim.shared.api.ESIM_INSTALL_DEEPLINK
import io.konekt.feature.esim.shared.api.EsimInstallScreenResource
import io.konekt.feature.purchase.shared.api.HistoryScreenResource
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.PlansScreenResource
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.konekt.feature.purchase.shared.api.TopUpForms
import io.konekt.feature.purchase.shared.api.TopUpScreenResource
import io.konekt.feature.shell.shared.api.HOME_DEEPLINK
import io.konekt.feature.shell.shared.api.ORDERS_DEEPLINK
import io.konekt.feature.shell.shared.api.PROFILE_DEEPLINK
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.serialization.serializer

// WHERE A DEEPLINK GOES, in ONE place rather than once per entry point.
//
// It was written inline in each runner, and the two had drifted: the desktop knew six deeplinks and
// the iOS one knew three, so the same `navigate` moved on one platform and printed "no handler" on
// the other. Nothing could notice — a route table built at a call site is not something a test can
// be handed.
//
// IT IS STILL A MAP AND NOT `kompot-navigation`'s graph, for the reason research §1.11 gives, and it
// is still a second copy of a pairing the server publishes at `/api/v1/navigation` — which is what
// `B-49` exists to delete. What changed is that the copy is now one object a guard can read, and
// `EveryScreenIsReachableTest` reads it.
object KonektRoutes {
    val map: Map<String, String> =
        mapOf(
            HOME_DEEPLINK to addressOf<HomeScreenResource>(),
            PLANS_DEEPLINK to addressOf<PlansScreenResource>(),
            ORDERS_DEEPLINK to addressOf<HistoryScreenResource>(),
            PROFILE_DEEPLINK to addressOf<ProfileScreenResource>(),
            TOP_UP_DEEPLINK to addressOf<TopUpScreenResource>(),
            ESIM_INSTALL_DEEPLINK to addressOf<EsimInstallScreenResource>(),
            // Matched by PREFIX: the server puts the number in the query, and a map keyed on the
            // whole string would need an entry per subscriber. `resolve` carries the tail across.
            LOGIN_CODE_DEEPLINK to addressOf<LoginCodeScreenResource>(),
            LOGIN_DEEPLINK to addressOf<LoginScreenResource>(),
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
