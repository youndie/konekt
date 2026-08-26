package io.konekt.http

import io.konekt.domain.ApiError
import io.konekt.domain.KonektException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import ru.workinprogress.katcher.Katcher

private val logger = LoggerFactory.getLogger("io.konekt.http.StatusPages")

// One place where a refusal becomes a status code, so a route never grows its own `onFailure`.
//
// A route unwraps its Result with `.getOrThrow()` and stops there. `.onFailure` belongs in a route
// only when a SPECIFIC error needs a body of its own — the rollback screen is the one case in this
// product — and everything else arrives here.
//
// The `when` has no `else`, and that is the mechanism rather than a style. KonektException is sealed,
// so a case added there and not mapped here fails to compile. A lookup table would have let it fall
// through to 500, which is the shape of failure a client reports as "it just errors".
fun KonektException.httpStatus(): HttpStatusCode =
    when (this) {
        is KonektException.NotFound -> HttpStatusCode.NotFound
        is KonektException.Validation -> HttpStatusCode.UnprocessableEntity
        is KonektException.Conflict -> HttpStatusCode.Conflict
        is KonektException.InsufficientFunds -> HttpStatusCode.Conflict
        is KonektException.Unauthorized -> HttpStatusCode.Unauthorized
        is KonektException.RateLimited -> HttpStatusCode.TooManyRequests
    }

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<KonektException> { call, cause ->
            if (cause is KonektException.RateLimited) {
                // A client told to slow down with no number picks one, and the number it picks is
                // usually "immediately".
                call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            }
            call.respond(cause.httpStatus(), ApiError(cause.code, cause.message ?: cause.code))
        }

        // THE ONE REFUSAL THAT IS NOT OURS, and leaving it out cost every route a 500.
        //
        // `call.receive<T>()` throws Ktor's BadRequestException when the body will not deserialise
        // — a missing field, a wrong type, a body shaped for a different endpoint — and so does a
        // typed @Resource parameter that will not parse. None of those is KonektException, so all of
        // them fell through to the handler below and answered "something went wrong on our side" for
        // a request that was wrong on the client's side.
        //
        // Found by pointing the kompot conformance kit at the running stand (B-24): it logs in with a
        // submit envelope, konekt's OTP verify takes a plain DTO, and the answer to that mismatch was
        // a 500. Every route that receives a body had it; nothing below the stand asked, because a
        // test sends a body its own code built.
        //
        // The cause's message names the Kotlin class it failed to build, so it is logged and not
        // returned: which internal type backs an endpoint is not the caller's business.
        exception<BadRequestException> { call, cause ->
            logger.info("rejected a malformed request on {}: {}", call.request.local.uri, cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("bad_request", "the request could not be read"),
            )
        }

        exception<Throwable> { call, cause ->
            // Logged in full and answered with nothing. An unexpected exception's message is written
            // for whoever wrote the code — it carries table names, identifiers, sometimes a query —
            // and a subscriber is not that reader. The trace id is what connects the two halves.
            logger.error("unhandled failure on ${call.request.local.uri}", cause)

            // AND REPORTED, which it was not, and could not have been.
            //
            // `Katcher.start` installs an uncaught-exception handler. A route's exception never
            // reaches one: THIS handler catches it and answers 500, which is the whole purpose of
            // StatusPages. So the server's katcher was correctly configured, correctly started, and
            // structurally unable to receive anything a route did — the ingest address answered and
            // nothing was ever going to be sent to it.
            //
            // `catch` is the manual half of the same library, and calling it here is the only place
            // that can. It is a no-op when katcher was never started (`appKey` empty), so a
            // deployment that chose not to report is unaffected rather than made to care.
            Katcher.catch(
                cause,
                context =
                    mapOf(
                        // The route as declared, not as requested: a path with an id in it would make
                        // every failing order its own crash group, and a group of one is a group
                        // nobody triages.
                        "route" to
                            (
                                call.attributes.getOrNull(
                                    AttributeKey<String>("RouteTemplate"),
                                ) ?: call.request.local.uri
                            ),
                        "method" to call.request.local.method.value,
                    ),
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("internal_error", "something went wrong on our side"),
            )
        }
    }
}
