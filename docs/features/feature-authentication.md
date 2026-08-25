---
id: feature-authentication
title: Sign-in by number and one-time code, and sessions that can be ended
type: feature
status: active
owner: unassigned
involved_services:
  - konekt-server
  - konekt-client
client_entries: []
api:
  - endpoint-auth
tags: [auth, session, otp]
---

# Sign-in by number and one-time code

`client_entries` is empty on purpose: there is **no sign-in screen** in this build. The client holds
the session (`KonektSession`) and the server answers with a session action, and nothing draws a form
yet. That is a gap, not a design — it is recorded here rather than left to be discovered by looking
for a screen document that does not exist.

## 1. Overview

A subscriber types a phone number, receives a six-digit code, and is signed in. There is no password
and no account creation step: **the first correct code for a number creates the subscriber and their
account together.**

Signing in yields two tokens. The access token is short-lived (15 minutes) and the refresh token
lasts 30 days, and both belong to a **session family** — a run of sessions descending from one
sign-in. Rotation replaces the token inside a family; logout and a detected theft end the family
itself. That is what makes "sign me out" mean something before a JWT expires.

The whole of this is konekt's own code. `kompot-auth` is **one action**, `update_session`, and not a
session system — see [research-architecture](../research/research-architecture.md) §1.5 and D4.

## 2. Business rules

* The answer to "send me a code" **must not depend on whether the number is known**. It is true by
  construction rather than by care: nothing in `RequestOtpUseCase` looks a subscriber up.
* A number is stored in one canonical form — digits only, no plus, no punctuation. A number stored
  two ways is a subscriber who can sign in twice and own two balances.
* One live code per number. A new request replaces the old one.
* A code is **never stored**; only an HMAC-SHA-256 of it, keyed with the deployment's secret. Keyed
  and not a plain digest: a six-digit code has a million possibilities, so an unkeyed SHA-256 of one
  is reversed by hashing all million. This defends against a leaked database, not against a
  compromised server — and the alternative defends against neither.
* Six attempts across the life of one code, then the number is locked for fifteen minutes. **The
  lockout survives a resend**, or "send again" is a reset button on the attempt counter.
* A code expires after five minutes and can be used once: it is consumed *before* the session is
  issued, so two requests arriving together cannot spend it twice.
* A refresh token is single-use. Exchanging one twice ends the family, because one of the two
  holders is not the subscriber and which one is unknowable.
* The family id travels in the **access** token too, and the authentication provider refuses a
  revoked one — at the cost of one indexed read per authenticated request.
* A subscriber is created with a **zero** balance, and nothing in the product adds money (`B-40`).

## 3. Flow

1. `POST /api/v1/auth/otp/request` — the code is generated, hashed, stored, and handed to
   `OtpDelivery` — a composite that **writes it to the log** and, when `DEV_REVEAL_OTP` is on, also
   keeps it in memory for the development route. No message is ever sent: the boundary of this system
   stops at the SMSC.
2. In development only, `GET /api/v1/dev/otp?msisdn=…` reads the code back. Without it there is no
   way to sign in at all.
3. `POST /api/v1/auth/otp/verify` — on success the subscriber and account are created if new, a
   family is opened, a refresh token is recorded, and the answer is an `update_session` **action**.
4. Every later request carries the access token; the client's bearer plugin refreshes on a 401
   through `POST /api/v1/auth/session/refresh`.
5. `POST /api/v1/auth/session/logout` (user tier) revokes the family.

All tiers are decided in `konektRoutes` in `server/src/main/kotlin/io/konekt/Application.kt`, as an
`AuthTier` value beside each group of routes — see [endpoint-auth](../api/endpoint-auth.md).

## 4. Code anchors

| Service | Code |
|---|---|
| konekt-server | `feature/auth-server-domain/src/main/kotlin/io/konekt/feature/auth/server/domain/` — the use cases, the policy, the ports |
| konekt-server | `feature/auth-server-data/src/main/kotlin/io/konekt/feature/auth/server/data/` — the routes, the JWT, the repositories, the SMSC mock |
| konekt-server | `feature/auth-shared-api/src/commonMain/kotlin/io/konekt/feature/auth/shared/api/` — the contract |
| konekt-server | `shared/server-common/src/main/kotlin/io/konekt/http/Principal.kt` — who is acting, and `ownedOr404` |
| konekt-client | `client/src/commonMain/kotlin/io/konekt/client/session/KonektSession.kt` and `.../net/KonektHttpClient.kt` |

## 5. Scenarios (BDD / test cases)

### Scenario: a subscriber signs in with the code they were sent
* **Given:** a number that has never been seen
* **When:** they request a code and post it back to `verify`
* **Then:** the answer is `200` carrying an `update_session` action with two tokens
* **And:** the subscriber and their account now exist, with a zero balance
* **Automated:** `AuthRoutingTest` — the case `a subscriber signs in with the code they were sent`

### Scenario: a code works once
* **Given:** a code that has just been used successfully
* **When:** it is posted to `verify` again
* **Then:** the answer is `422` with `validation_failed` / `that code is not right`
* **Automated:** `AuthRoutingTest`

### Scenario: an unknown number is answered exactly like a known one
* **Given:** one number that is a subscriber and one that has never been seen
* **When:** a code is requested for each
* **Then:** the two answers are identical — same status, same body, same work done
* **Automated:** `AuthRoutingTest`, and at the use-case level `RequestOtpUseCaseTest`

### Scenario: six wrong codes lock the number, and the answer says for how long
* **Given:** an outstanding code
* **When:** six wrong codes are posted
* **Then:** the sixth answers `429` with `Retry-After: 900` and `rate_limited`
* **And:** a resend during the lockout is refused as well, rather than issuing a fresh budget
* **Automated:** `AuthRoutingTest`, `VerifyOtpUseCaseTest`, `RequestOtpUseCaseTest`

### Scenario: an expired code says so rather than pretending to be wrong
* **Given:** a code issued more than five minutes ago
* **When:** it is posted to `verify`
* **Then:** the answer is `422` with `that code has expired, ask for a new one`
* **And:** that wording reveals nothing — an expired challenge exists for any number that asked
* **Automated:** `VerifyOtpUseCaseTest`

### Scenario: the code is stored hashed and never as itself
* **Given:** a code has been issued
* **When:** the stored challenge is read
* **Then:** the plain code does not appear in it
* **Automated:** `RequestOtpUseCaseTest`

### Scenario: refreshing returns a new pair and retires the old refresh token
* **Given:** a signed-in subscriber
* **When:** they exchange their refresh token
* **Then:** they receive a new pair, and the old refresh token answers `401`
* **Automated:** `SessionRotationTest`

### Scenario: a refresh token used twice ends the family
* **Given:** a refresh token that has already been exchanged
* **When:** it is presented again
* **Then:** the family is revoked with reason `reuse_detected`, and **both** the thief's and the
  subscriber's tokens stop working — `401` with `this session has ended`
* **Automated:** `SessionRotationTest`

### Scenario: logout makes the access token stop working before it expires
* **Given:** a signed-in subscriber holding a valid access token
* **When:** they call logout
* **Then:** the same access token answers `401` on the next request, without waiting for its 15
  minutes
* **Automated:** `SessionRotationTest`

### Scenario: each token is refused where the other belongs
* **Given:** an access token and a refresh token from one sign-in
* **When:** each is presented where the other belongs
* **Then:** both are refused — the `typ` claim is what separates them
* **Automated:** `SessionRotationTest`

### Scenario: one family ending does not end another
* **Given:** two subscribers signed in
* **When:** one of them logs out
* **Then:** the other's session still works
* **Automated:** `SessionRotationTest`

### Scenario: the stand signs in end to end, through the development code route
* **Given:** the compose stand with `DEV_REVEAL_OTP=true`
* **When:** a scenario calls `Stand.signIn`
* **Then:** request, read-back and verify all succeed against the running server
* **Automated:** `PurchaseScenarioTest` (through `Stand`)

### Scenario: a code actually reaches a phone
* **Given:** any deployment of this product
* **When:** a code is requested
* **Then:** **nothing is delivered.** `OtpDelivery` logs, and optionally records the code for the
  development route. There is no SMSC and this scenario is not covered by anything, manual or
  otherwise.

## 6. Out of scope

* Any sign-in screen or form. There is no client UI for this feature.
* Storing tokens durably. `InMemorySessionStore` survives nothing; the keychain and
  `EncryptedSharedPreferences` are named as a seam and not implemented, because this build has
  neither target.
* Rate limiting per IP or per device. The only budget is per number.
* Anything that adds money to the account created here (`B-40`).

## 7. Quirks

- **`verify` and `refresh` answer an ACTION, not a DTO**, through `respondKompotAction`. A plain
  `call.respond` drops the `"type"` discriminator at the root and the client silently does nothing —
  with a 200.
- **The OTP pepper is the JWT secret.** One secret doing two jobs, acceptable because both are "this
  deployment's server-side key" and neither leaves the process. A deployment with a key-management
  story gives them separate keys; that is a row in the operator material (`B-30`), not a change here.
- **The development route reads any subscriber's code with no credential.** It is absent unless
  `DEV_REVEAL_OTP` is exactly `"true"`. If it ever ships enabled, it **is** the authentication system.
- **The code is written into the log, as a warning, deliberately.** It is a credential in a log, and
  it is there because there is no SMSC; the class is named `LoggingOtpDelivery` so nobody wires it in
  without noticing. `RevealedCodes` is unbounded in memory by design — one short string per number
  that has asked since the process started — and does not exist in a build without the dev flag.
- **`Msisdn.parse` refuses outside 7–15 digits**, which is a typo check and not a country check.
- **The reuse arbitration is a conditional `UPDATE`, never a read-then-write.** Two exchanges arriving
  together would both pass an `if` in Kotlin; only one can win the `UPDATE`. The loser is treated as
  reuse, which is right — from the server's side a race and a theft are indistinguishable.
