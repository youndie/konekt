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
import io.ktor.util.cio.ChannelWriteException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
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

        // THE SAME REFUSAL ONE LAYER DOWN, and the handler above does not catch it.
        //
        // `BadRequestException` is what Ktor wraps a failed `call.receive<T>()` in. Several routes
        // here do not use `receive<T>()`: a form submit and a wizard step read the body as TEXT and
        // decode it with the application's own `Json`, because the polymorphic scope that turns
        // `{"type":"…"}` into an action or a `FieldValue` is the application's and not
        // ContentNegotiation's. Nothing wraps that, so a malformed body reached the handler below and
        // answered "something went wrong on our side" — for a request that was wrong on the client's
        // side, on every endpoint that takes an action or a form.
        //
        // Found by sending one: a step posted with a truncated body answered 500 and logged an
        // unhandled failure, which is a line that looks like a server defect to whoever reads the log.
        //
        // THE TRADE, NAMED. This also catches a stored payload that will not parse — petich decodes
        // its own saga payloads — and that IS a server fault reported as a client one. It is taken
        // because a malformed request is orders of magnitude commoner than a corrupt row, and because
        // the alternative is remembering to wrap the decode in every route that reads a body by hand:
        // the same thing the handler above exists because somebody forgot. Either way the cause is
        // logged, so a burst of these on one endpoint is visible.
        exception<SerializationException> { call, cause ->
            logger.info("could not read the body of {}: {}", call.request.local.uri, cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("bad_request", "the request could not be read"),
            )
        }

        // A CLIENT THAT WENT AWAY IS NOT A DEFECT, and this build was reporting it as one.
        //
        // The realtime stream is held open for as long as a subscriber has the screen. Ktor's CIO
        // wraps a broken pipe on it in a `ChannelWriteException`, which is a Throwable like any other
        // — so it reached the handler below, was logged at ERROR, and became a CRASH GROUP in katcher.
        //
        // WHICH DISCONNECTS DO IT, measured rather than assumed. Killing the desktop client mid-push
        // filed a report; closing its window did not. The difference is whether the server was
        // writing when the socket went: a graceful close ends the read side between frames and
        // nothing throws. So this is the UNGRACEFUL half — a killed process, a closed laptop, a phone
        // that lost signal — raced against the push cadence, which on a screen that updates every few
        // seconds is a large share of real endings and none of the tidy ones.
        //
        // That is still worth silencing rather than triaging: it is a report about a subscriber's
        // network, filed under a product's defects, and reports nobody can act on are what teach an
        // operator to stop reading the ones they can.
        //
        // NARROW ON PURPOSE. Not `IOException` — a failure talking to the database or the broker is
        // an IOException too, and silencing those would be trading one blind spot for a larger one.
        // What this type says, and nothing else does, is that the socket to THIS caller is gone.
        //
        // NOTHING IS SENT. There is no channel left to answer on; a `respond` here would throw the
        // same exception again, from inside the handler for it.
        exception<ChannelWriteException> { call, cause ->
            logger.debug("the client on {} went away mid-response", call.request.local.uri, cause)
        }

        // A CANCELLED CALL IS NOT A FAILURE, AND IT MUST KEEP PROPAGATING. A subscriber that closes
        // `/api/v1/realtime` cancels the coroutine serving it; the exception is
        // `JobCancellationException`, and the handler below would have reported it to katcher as a
        // crash — with the job's identity hash in the message, so every closed stream was its OWN
        // group, and sixteen of them pushed the one real group off the collector's first page. That
        // is how it was found: an operator's page that no longer showed the failure that was there.
        //
        // Rethrown rather than swallowed: cancellation is how structured concurrency unwinds, and a
        // handler that answers it has turned a cancelled job into a completed one.
        exception<CancellationException> { call, cause ->
            logger.debug("the call on {} was cancelled", call.request.local.uri, cause)
            throw cause
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
