---
id: B-38
title: "Refresh and logout: a token pair that can be ended"
status: open
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

- AC: a refresh token presented twice ends the family, and both tokens stop working — asserted
  through the routes rather than against the table.
- AC: logout makes the access token stop working before it expires.
- AC: an access token presented to the refresh endpoint is refused, and the reverse.
- Anchors: `feature/auth-server-data/`, `shared/db/src/main/resources/db/migration/`.

Background: [B-06](B-06-otp-login.md).
