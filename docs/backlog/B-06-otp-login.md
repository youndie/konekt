---
id: B-06
title: "Number and OTP sign-in, written from scratch because kompot-auth is one action"
status: wip
priority: P0
size: L
stage: stage-m0-wire
epic: feature-authentication
blocked_by: [B-02]
---

# B-06 — Number and OTP sign-in, written from scratch because kompot-auth is one action

The brief says "session through kompot-auth". Research §1.5 read the module: it is one serialisable
action, `update_session`, and nothing else. OTP issue, OTP check, rate limiting, token storage,
refresh and logout are all konekt's, behind a Ktor `Authentication` provider.

- **The decision and its reason.** `update_session` is used for exactly what it is — the one action
  that hands the client a new session after a successful check — and everything around it is ours.
  Saying this in the backlog is the point: an item written from the brief would have been called
  "wire up kompot-auth" and sized at a day.
- The rejected alternative, an off-the-shelf IdP, answers a question this build is not asking and
  moves the session out of the stack being demonstrated.
- Not covered: the SMSC. The OTP is written to the log and returned by a development-only endpoint,
  per the brief's boundary table.

- AC OK (server half): a wrong code, an expired challenge and a number nobody has asked about all
  answer the same type, the same field and the same words — asserted as an equality between the
  answers rather than by reading them. The parity is **structural**: `RequestOtpUseCase` has no
  `SubscriberRepository` in its constructor, so there is no lookup, no branch and no difference in
  the work done. A subscriber is created on the first successful *verify* instead, which is what makes
  that true rather than careful.
- AC OK (server half): the sixth wrong code answers 429 with `Retry-After: 900`, driven through a real
  route. The lockout survives a resend — otherwise "send again" is a reset button on the attempt
  counter — and it is checked *before* the code is compared, so a correct guess cannot end a lockout.
- AC OK: a correct code answers `update_session` through `respondKompotAction`, asserted on the
  `"type"` discriminator in the body. A plain `call.respond` drops it at the root while nested children
  serialise perfectly, so the client receives an unknown action and does nothing — with a 200.
- AC PENDING, **client half**: "and the client stores it" needs a client module, which does not exist.
  It arrives with `B-04`/`B-07`.
- Also done, carried from `B-34`: `ownedOr404` in `:shared:server-http` — 404 and not 403 for another
  subscriber's resource, because a 403 confirms the resource exists and hands out an enumeration
  oracle. The rule lives in one place now; the first owner-scoped route uses it.
- Not covered: refresh and logout. Tokens are issued as a pair with a `typ` claim, so a refresh token
  is refused where an access token is required — but there is no refresh endpoint, no rotation and no
  revocation. `B-38`.
- Anchors: `feature/auth-shared-api/`, `feature/auth-server-domain/`, `feature/auth-server-data/`,
  `shared/server-http/src/main/kotlin/io/konekt/http/Principal.kt`.

## What building the first feature moved

The vertical of `D12` met reality, and three things had to move — each because a feature module
cannot depend on the thing that composes it:

- **`KonektClock` to `:shared:domain`.** It was in `:server`, which a feature's `-server-domain`
  cannot see. Time is a domain dependency, so that is where it belongs.
- **`:shared:db`.** `subscriber` and `account` belong to no single feature — sign-in creates them,
  balance reads them, orders spend against them — so a feature declaring its own copy would be a
  second schema that agrees until it does not. The migrations, `DatabaseFactory` and the Postgres
  harness moved with them, the harness as test fixtures so there is one container lifecycle rather
  than one per module.
- **`:shared:server-http`.** The principal, the owner check and the `StatusPages` mapping. "Who is
  signed in" is not the auth feature's private business; it is the first thing every other route asks.

None of this was visible from the design. It became visible on the first feature, which is the
argument for building one end to end before building four.

Background: [research-architecture](../research/research-architecture.md) §1.5, D4.
