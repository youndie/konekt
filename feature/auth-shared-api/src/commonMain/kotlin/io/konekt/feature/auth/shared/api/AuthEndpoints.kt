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

@Resource("/api/v1/auth/session")
class AuthSession {
    // Exchanges a refresh token for a new pair and invalidates the one presented. Public tier: the
    // refresh token IS the credential, so requiring an access token here would defeat the purpose —
    // the whole point is to be usable once the access token has expired.
    @Resource("refresh")
    class Refresh(
        val parent: AuthSession = AuthSession(),
    )

    // Ends the session family. Authenticated tier, because it acts on whoever is calling.
    @Resource("logout")
    class Logout(
        val parent: AuthSession = AuthSession(),
    )
}

// Development only, and mounted only when the SMSC mock is configured to reveal codes. It exists
// because the boundary of this system stops at the SMSC: no message is ever sent, so without this
// there is no way to sign in at all.
@Resource("/api/v1/dev/otp")
class DevOtp(
    val msisdn: String,
)

// THE WAY IN, AS TWO SCREENS AND TWO SUBMITS.
//
// The screens are forms; the submits are `submit`-kind endpoints answering a `KompotAction` the client
// feeds back into its handler chain — a `navigate` for the first step and an `update_session` for the
// second. That is the toolkit's own shape (SPEC §16.1) and it is what makes a login screen a server
// response rather than the one screen everybody hand-writes.
@Resource("/api/v1/screens/login")
class LoginScreenResource

// The code step carries the number it was sent to, because verifying needs both and this build keeps
// nothing between the two steps. A server that remembered which number was asking would be a second
// place the answer lives.
@Resource("/api/v1/screens/login/code")
class LoginCodeScreenResource(
    // DEFAULTED TO EMPTY, and that is behaviour rather than laxity: arriving at the code step
    // without a number is a real state — a stale link, a restarted application, a conformance
    // walk asking for every GET it can see — and the answer is the first step rather than a 400.
    // A required parameter would make the screen unreachable to anything that had not just come
    // from step one.
    val msisdn: String = "",
    // WHY THE PREVIOUS ATTEMPT WAS REFUSED, as a CODE and never as a sentence. Absent on first arrival.
    //
    // The sentence used to travel here, and two things were wrong with that. It has spaces, so the URL
    // was malformed and the client answered "Unsupported HTTP version: code" — a message naming
    // neither the address nor the cause. And anybody who can hand somebody a link could then put
    // arbitrary text on this product's login screen, which is a small phishing primitive for nothing.
    //
    // A code means the copy stays composed on the server, where D15 puts it, and the query carries a
    // word from a list this build owns.
    val error: String? = null,
)

@Resource("/api/v1/auth/login")
class LoginSubmit

@Resource("/api/v1/auth/login/code")
class LoginCodeSubmit

// The deeplinks the login submits answer with, spelled once for the same reason `PLANS_DEEPLINK` is.
const val LOGIN_DEEPLINK: String = "app://login"
const val LOGIN_CODE_DEEPLINK: String = "app://login/code"

// THE FORM IDS AND FIELD IDS OF THE LOGIN FLOW, spelled once because three parties spell them: the
// screen that declares the schema, the client that maps a form id to the address it posts to, and the
// route that reads the values back out. A typo in any one is a submit button that posts nowhere or a
// field the server never sees — and neither fails to compile.
// The refusals the code step can name. A closed list, because the screen turns each into a sentence
// and an unknown word must draw no banner rather than an empty one.
object LoginRefusals {
    const val WRONG_CODE = "wrong_code"
}

object LoginForms {
    const val NUMBER = "login"
    const val CODE = "login-code"

    const val FIELD_MSISDN = "msisdn"
    const val FIELD_CODE = "code"
}
