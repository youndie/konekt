---
id: B-38
title: "Refresh and logout: a token pair that can be ended"
status: done
priority: P1
size: M
stage: stage-m0-wire
epic: feature-authentication
blocked_by: [B-06]
---

# B-38 — Refresh and logout: a token pair that can be ended

`B-06` issues an access token and a refresh token, distinguished by a `typ` claim so the verifier
refuses a refresh token where an access token is required. What it does not have is any way to use
the second one, or to stop using either.

Today that means a session lasts fifteen minutes and then the subscriber signs in again with a new
one-time code, and a stolen token is valid until it expires because nothing can revoke it.

- **The decision and its reason.** A refresh endpoint that rotates: presenting a refresh token
  returns a new pair and invalidates the one presented. Rotation is what makes theft *detectable* —
  a refresh token used twice means one of the two holders is not the subscriber, and the right answer
  to that is to end the whole family rather than to serve whichever request arrived second.
- Rotation needs state, so this is a table: the token's id, its family, and whether it has been used.
  That is exactly the part `B-06` did not build, because a stateless pair is a half-measure that
  looks finished.
- Logout ends the family. Without the table there is nothing to end, which is why the two are one
  item rather than two.
- The rejected alternative is a short access token and no refresh at all — a one-time code every
  fifteen minutes. It is genuinely secure and it is why nobody would use the product.
- Not covered: sessions listed and revoked individually from a settings screen. That needs a screen.

- AC OK: a refresh token presented twice ends the family, and the pair issued a moment earlier dies
  with it — including its **access** token, which is the part a stateless design cannot do. Driven
  through the routes; against the tables it would have been a test of my own SQL.
- AC OK: logout refuses an access token that still has fifteen minutes on it.
- AC OK: each token is refused where the other belongs, both directions.
- Also asserted: ending one family leaves another alone. The revoke is a `WHERE` clause, and a
  `WHERE` clause is exactly the thing that is right until somebody widens it.
- Anchors: `feature/auth-server-domain/src/main/kotlin/io/konekt/feature/auth/server/domain/RefreshSessionUseCase.kt`,
  `feature/auth-server-data/src/main/kotlin/io/konekt/feature/auth/server/data/ExposedSessionRepository.kt`,
  `shared/db/src/main/resources/db/migration/V4__session_family.sql`,
  `feature/auth-server-data/src/test/kotlin/io/konekt/feature/auth/server/data/SessionRotationTest.kt`.

## The two decisions worth not re-litigating

**The arbitration happens in the database, not in Kotlin.** Two exchanges of one refresh token
arriving together both pass a read-then-write; only one can satisfy `used_at IS NULL` inside a
conditional `UPDATE`, and the row count is the answer. The loser is treated as reuse — which is
correct, because from the server's side a race and a theft look identical and the safe reading is the
second one. An honest client that fired two refreshes at once pays one sign-in for it.

**Logout costs one indexed read per authenticated request, and that is the price of logout working at
all.** A JWT is valid until it expires, so the access token carries its family id and the
authentication provider refuses a principal whose family has been revoked. The alternative is a short
access lifetime and a logout that takes effect when it expires — which is what "stateless logout"
always means, and it is worth naming rather than implying. The cost is chosen once and paid forever,
so it is written here rather than discovered in a profile.

Background: [B-06](B-06-otp-login.md).
