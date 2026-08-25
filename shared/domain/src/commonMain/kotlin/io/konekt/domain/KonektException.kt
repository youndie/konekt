package io.konekt.domain

import kotlinx.serialization.Serializable

// What a caller is told when something is refused, on the wire.
//
// A code as well as a message, because the two have different readers: the message is for a person
// and may be rewritten or translated at will, while the code is what a client branches on and is
// therefore part of the contract. Not problem+json — this product has one client and one server, and
// a media type nobody validates buys nothing.
@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

// Every refusal this server can produce, as a closed set.
//
// Closed on purpose: the mapping to HTTP is a `when` with no `else`, so adding a case here and
// forgetting to map it does not compile. That is the whole design — a mapping table would let a new
// exception fall through to 500 quietly, which is exactly the shape of failure nobody notices until
// a client reports "it just errors".
sealed class KonektException(
    val code: String,
    message: String,
) : Exception(message) {
    // The resource does not exist, OR it exists and belongs to somebody else. Deliberately the same
    // answer: a 403 on another subscriber's order confirms that the order exists, which is an
    // enumeration oracle for anyone who wants one.
    class NotFound(
        entity: String,
    ) : KonektException("not_found", "$entity was not found")

    // The request is well-formed and its contents are wrong. `field` is what the client highlights;
    // form-core validates locally, so anything reaching here is a rule only the server knows.
    class Validation(
        val field: String?,
        message: String,
    ) : KonektException("validation_failed", message)

    // The current state of the world refuses this, and retrying the same request will not help until
    // something changes.
    class Conflict(
        message: String,
    ) : KonektException("conflict", message)

    // Its own case rather than a Conflict, because it is the one refusal a subscriber can act on and
    // the screen offers them a top-up.
    class InsufficientFunds(
        val shortfall: Money,
    ) : KonektException("insufficient_funds", "the balance does not cover this")

    // No session, or one that no longer means anything.
    class Unauthorized(
        message: String = "authentication required",
    ) : KonektException("unauthorized", message)

    // Too many attempts. Carries the wait, because a client told to slow down with no number picks
    // one, and the number it picks is usually "immediately".
    class RateLimited(
        val retryAfterSeconds: Long,
    ) : KonektException("rate_limited", "too many attempts, try again in $retryAfterSeconds s")
}
