package io.konekt.feature.auth.server.data

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.ktor.respondKompotAction
import io.konekt.feature.auth.server.domain.LogoutUseCase
import io.konekt.feature.auth.server.domain.Msisdn
import io.konekt.feature.auth.server.domain.OtpRepository
import io.konekt.feature.auth.server.domain.RefreshSessionUseCase
import io.konekt.feature.auth.server.domain.RequestOtpUseCase
import io.konekt.feature.auth.server.domain.VerifyOtpUseCase
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.AuthSession
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RefreshSessionRequest
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.konekt.http.sessionFamilyId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: public, both routes, and that is a decision rather than an omission.
//
// A route outside `authenticate { }` has a tier too — "anyone" — and the way that goes wrong is by
// nobody choosing it. These two are the way in, so they cannot be behind a session; what protects
// them instead is the lockout in the use cases and the fact that neither answer depends on whether
// the number is known.
fun Route.authRoutes() {
    val requestOtp by inject<RequestOtpUseCase>()
    val verifyOtp by inject<VerifyOtpUseCase>()
    val json by inject<Json>()

    post<AuthOtp.Request> {
        val body = call.receive<RequestOtpRequest>()
        // .getOrThrow(), and nothing else. StatusPages maps every refusal this can produce, so an
        // onFailure branch here would be a second, quieter copy of that mapping.
        call.respond(requestOtp(body.msisdn).getOrThrow())
    }

    post<AuthOtp.Verify> {
        val body = call.receive<VerifyOtpRequest>()
        val session = verifyOtp(VerifyOtpUseCase.Params(body.msisdn, body.code)).getOrThrow()

        // An ACTION rather than a DTO, through respondKompotAction rather than call.respond. A plain
        // respond resolves the serialiser from the concrete class and drops the "type" discriminator
        // at the root, and the client then receives an unknown action and does nothing at all — with
        // a 200.
        call.respondKompotAction(
            json,
            UpdateSessionAction(accessToken = session.accessToken, refreshToken = session.refreshToken),
        )
    }
}

// AUTH TIER, per route and written down rather than read off the indentation:
//   refresh — PUBLIC. The refresh token IS the credential, and the whole point is to work once the
//             access token has expired, so requiring one here would defeat it.
//   logout  — USER TOKEN. It acts on whoever is calling, and the family it ends comes from the token
//             rather than from the request body: a body-supplied family id is a route that ends
//             anybody's session for anybody who asks.
fun Route.sessionRoutes() {
    val refreshSession by inject<RefreshSessionUseCase>()
    val json by inject<Json>()

    post<AuthSession.Refresh> {
        val body = call.receive<RefreshSessionRequest>()
        val session = refreshSession(body.refreshToken).getOrThrow()

        call.respondKompotAction(
            json,
            UpdateSessionAction(accessToken = session.accessToken, refreshToken = session.refreshToken),
        )
    }
}

fun Route.authenticatedSessionRoutes() {
    val logout by inject<LogoutUseCase>()

    post<AuthSession.Logout> {
        // The family comes from the verified token, never from the body.
        logout(call.sessionFamilyId()).getOrThrow()
        call.respond(HttpStatusCode.NoContent)
    }
}

// Mounted only when the SMSC mock reveals codes, which is never in production. It exists because the
// boundary of this system stops at the SMSC: no message is ever sent, so without this there is no way
// to sign in at all.
//
// AUTH TIER: public, and that is only acceptable because the route does not exist unless a
// development flag is set. A machine route that reads any subscriber's one-time code is the whole
// authentication system if it ships.
fun Route.devOtpRoutes(revealed: RevealedCodes) {
    val challenges by inject<OtpRepository>()

    get<DevOtp> { params ->
        val msisdn = Msisdn.parse(params.msisdn)
        val code = revealed.of(msisdn)
        val challenge = challenges.find(msisdn)

        if (code == null || challenge == null) {
            call.respond(HttpStatusCode.NotFound, "no outstanding code")
            return@get
        }

        call.respond(DevOtpResponse(msisdn.value, code, challenge.expiresAt.toEpochMilliseconds()))
    }
}
