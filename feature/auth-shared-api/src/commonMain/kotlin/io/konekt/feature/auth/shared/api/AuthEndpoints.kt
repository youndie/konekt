package io.konekt.feature.auth.shared.api

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// The paths of this feature, written once.
//
// Not `object Endpoints { const val … }`, which is the shape the nearest prior art used and which
// still writes a parameterised path twice — once as a Ktor template and once as a builder function,
// with nothing checking that the two agree. A @Resource class is the path, and both sides construct
// it from the same type: a renamed segment becomes a compile error rather than a 404 in somebody's
// hands.
//
// The check before a pull request is a grep for "/api/" outside a *-shared-api module. It must find
// nothing.
@Resource("/api/v1/auth/otp")
class AuthOtp {
    @Resource("request")
    class Request(
        val parent: AuthOtp = AuthOtp(),
    )

    @Resource("verify")
    class Verify(
        val parent: AuthOtp = AuthOtp(),
    )
}

// Development only, and mounted only when the SMSC mock is configured to reveal codes. It exists
// because the boundary of this system stops at the SMSC: no message is ever sent, so without this
// there is no way to sign in at all.
@Resource("/api/v1/dev/otp")
class DevOtp(
    val msisdn: String,
)
