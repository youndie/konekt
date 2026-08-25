package io.konekt.http

import io.konekt.domain.KonektException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal

// Who is acting, taken from the token once and read the same way everywhere.
//
// It lives in a module every feature can see rather than inside the auth feature, because "who is
// signed in" is not the auth feature's private business — it is the first thing every other route
// asks. The alternative is each feature digging the claim out of the principal itself, which is four
// spellings of one rule.
// Not `: Principal` — Ktor 3 dropped the marker interface and any type may be a principal now, which
// is why this is a plain data class.
data class SubscriberPrincipal(
    val subscriberId: String,
)

// Throws rather than returning null. A route that reached this without a principal is a route that
// was not wrapped in `authenticate { }`, and answering 401 is more useful than a null that turns into
// a NullPointerException three lines later.
fun ApplicationCall.subscriberId(): String =
    principal<SubscriberPrincipal>()?.subscriberId
        ?: throw KonektException.Unauthorized()

// The rule that every owner-scoped route follows, in one place so it is followed the same way.
//
// `authenticate { }` proves the caller is SOMEBODY. It says nothing about whether the thing they are
// asking for is theirs, and that second check is the one that gets skipped — it lives in the use
// case, beside the owner, not in the route.
//
// 404 AND NOT 403, deliberately. A 403 on another subscriber's order confirms that the order exists,
// which hands anyone who wants one an enumeration oracle: ask for a million ids and keep the ones
// that answer 403. The price is that a subscriber who genuinely lost access cannot tell the two
// apart, and that is the right way round.
fun <T : Any> T?.ownedOr404(
    entity: String,
    ownerId: String,
    ownerOf: (T) -> String,
): T {
    if (this == null || ownerOf(this) != ownerId) {
        throw KonektException.NotFound(entity)
    }
    return this
}
