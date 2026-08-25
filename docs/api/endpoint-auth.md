---
id: endpoint-auth
title: Authentication and sessions
type: api_endpoints
status: active
services:
  - konekt-server
contract_source:
  - konekt:feature/auth-shared-api AuthOtp, AuthSession, DevOtp
  - konekt:feature/auth-shared-api AuthDto.kt (request and response bodies)
parent_feature: feature-authentication
---

# API: authentication and sessions

> The **complete** route reference for this feature. URL shapes live in the `@Resource` classes named
> in `contract_source`; bodies live beside them in `AuthDto.kt`. There is no generated schema — that
> is `B-23` — so this document is the reference a person reads.
>
> Every status code and error string below was read out of the source on 2026-08-25. Where a claim is
> not backed by a line of code it says so.

## Routes — all of them, no exceptions

| Method and path | Auth tier | Where the tier is set | Purpose |
|---|---|---|---|
| `POST /api/v1/auth/otp/request` | **public** | `konektRoutes`, `AuthTier.PUBLIC` (`authRoutes()`) | ask for a one-time code |
| `POST /api/v1/auth/otp/verify` | **public** | `konektRoutes`, `AuthTier.PUBLIC` (`authRoutes()`) | exchange a code for a session |
| `POST /api/v1/auth/session/refresh` | **public** | `konektRoutes`, `AuthTier.PUBLIC` (`sessionRoutes()`) | exchange a refresh token for a new pair |
| `POST /api/v1/auth/session/logout` | **user token** | `konektRoutes`, `AuthTier.USER` (`authenticatedSessionRoutes()`) | end the caller's session family |
| `GET /api/v1/dev/otp?msisdn=…` | **public, and mounted only when `DEV_REVEAL_OTP=true`** | `devOtpRouteGroup`, `AuthTier.PUBLIC`, appended to the table only when the flag is on | read back the code the SMSC would have carried |

The tier is a **value**, not the indentation of a `routing { }` block: `konektRoutes` in
`server/src/main/kotlin/io/konekt/Application.kt` pairs an `AuthTier` with the routes that sit at it,
and `mountKonektRoutes` is the one place a tier becomes an `authenticate { }`.

**Why three of these are public, stated rather than left to the indentation.** `request` and `verify`
are the way in, so they cannot be behind a session; what protects them is the lockout in the use
cases and the fact that neither answer depends on whether the number is known. `refresh` is public
because **the refresh token is the credential** — requiring an access token would defeat the point,
which is to work once the access token has expired. `logout` is the only one that acts on whoever is
calling, and the family it ends comes from the verified token and never from the body: a
body-supplied family id is a route that ends anybody's session for anybody who asks.

**The development route is the whole authentication system if it ships.** It reads any subscriber's
outstanding code with no credential at all. It exists because the boundary of this product stops at
the SMSC — no message is ever sent — so without it there is no way to sign in. It is absent unless
`DEV_REVEAL_OTP` is exactly `"true"`; the compose stand sets it, and nothing else should.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `POST /api/v1/auth/otp/request` | `feature/auth-server-data/src/main/kotlin/io/konekt/feature/auth/server/data/AuthRouting.kt` — `authRoutes()` |
| `POST /api/v1/auth/otp/verify` | same file, `authRoutes()` |
| `POST /api/v1/auth/session/refresh` | same file, `sessionRoutes()` |
| `POST /api/v1/auth/session/logout` | same file, `authenticatedSessionRoutes()` |
| `GET /api/v1/dev/otp` | same file, `devOtpRoutes()` |
| the JWT provider itself | `feature/auth-server-data/src/main/kotlin/io/konekt/feature/auth/server/data/AuthModule.kt` — `configureAuthentication` |
| token minting and verification | `feature/auth-server-data/src/main/kotlin/io/konekt/feature/auth/server/data/JwtSessions.kt` |

## Request and response bodies

Do not copy the fields; the file is
`feature/auth-shared-api/src/commonMain/kotlin/io/konekt/feature/auth/shared/api/AuthDto.kt` —
`RequestOtpRequest`, `RequestOtpResponse`, `VerifyOtpRequest`, `RefreshSessionRequest`,
`DevOtpResponse`.

Two answers are **not** DTOs and that is load-bearing:

- `verify` and `refresh` answer kompot's `UpdateSessionAction`, written with `respondKompotAction`.
  A plain `call.respond` resolves the serialiser from the concrete class and drops the `"type"`
  discriminator at the root, and the client then receives an unknown action and does nothing — with a
  200. The client decodes it with `decodeKompotAction`, not as a pair of strings.
- `logout` answers **204 with no body**.

The access token carries `sub` (subscriber id), `fam` (session family) and `typ` (`access` or
`refresh`); the refresh token additionally carries `jti`. The `typ` claim is what makes a refresh
token unusable where an access token belongs, and the `fam` claim is what makes logout mean anything
before the token expires — see the quirks.

## Errors

Every refusal is a `KonektException` mapped once in
`shared/server-common/src/main/kotlin/io/konekt/http/StatusPages.kt`; the body is
`ApiError(code, message)`.

| Condition | Status | Body (`code` / `message`) |
|---|---|---|
| the number is not a number (`request` and `verify` alike) | `422` | `validation_failed` / `that does not look like a phone number` |
| asking for a code again inside the resend window (60 s) | `429` + `Retry-After` | `rate_limited` / `too many attempts, try again in N s` |
| a locked number asking again | `429` + `Retry-After` | `rate_limited` / same |
| a wrong code, **and** a code for a number nobody asked about, **and** no outstanding code | `422` | `validation_failed` / `that code is not right` |
| a code that has expired (5 min) | `422` | `validation_failed` / `that code has expired, ask for a new one` |
| the sixth wrong code | `429` + `Retry-After: 900` | `rate_limited` / `too many attempts, try again in 900 s` |
| refresh with a token this server did not sign, an expired one, an access token, an unknown family, or an expired stored token | `401` | `unauthorized` / `authentication required` |
| refresh with a token that has already been used, or one whose family was revoked | `401` | `unauthorized` / `this session has ended` |
| any route in the user tier without a valid token, or with one whose family was revoked | `401` | Ktor's own challenge — **not** an `ApiError` body |
| `GET /api/v1/dev/otp` with nothing outstanding | `404` | the literal `no outstanding code` — *how ContentNegotiation frames a bare `String` here is not asserted by any test* |

The numbers above (six attempts, five minutes, fifteen minutes, sixty seconds, six digits) are the
defaults of `OtpPolicy` in
`feature/auth-server-domain/src/main/kotlin/io/konekt/feature/auth/server/domain/OtpChallenge.kt`,
bound as `single { OtpPolicy() }`. Nothing overrides them today.

## Quirks

- **A wrong code, an expired-and-gone code and a number nobody has ever requested one for all answer
  the same thing.** Any other wording is a question about the number that anybody may ask.
- **An expired code is deliberately distinguished from a wrong one.** It tells the subscriber what to
  do, and reveals nothing: an expired challenge exists for any number that requested one.
- **A lockout survives a resend.** Otherwise "send again" is a reset button on the attempt counter.
- **Logout works on the access token, and it costs one indexed read per authenticated request.** The
  provider looks the family up on every call, because a JWT is valid until it expires. That is the
  price of logout meaning anything at all, and it is chosen once and paid forever.
- **A refresh token exchanged twice ends the family**, and the arbitration is a conditional `UPDATE`
  rather than a read-then-write: two exchanges arriving together would both pass an `if` in Kotlin.
  From the server's side a race and a theft look identical, and the safe reading is the second.
- **No test asserts that a route sits at the right tier.** `SessionRotationTest` builds its own
  `authenticate(AUTH_JWT)` block, so it proves the token logic and not the mount, and the e2e stand
  always sends a bearer token. Since the tier became a value in `konektRoutes` it is at least
  *quotable* — a generator can read it — but nothing compares it against what the routes need.
