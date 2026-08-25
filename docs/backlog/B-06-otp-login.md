---
id: B-06
title: "Number and OTP sign-in, written from scratch because kompot-auth is one action"
status: open
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

- AC: a wrong code returns the same shape and timing as a right one for an unknown number; a correct
  code returns a session through `update_session` and the client stores it.
- AC: six wrong codes in a row for one number lock further attempts for a stated interval, and the
  screen says so.
- Anchors: `server/src/main/kotlin/io/konekt/auth/`, `client/src/commonMain/kotlin/io/konekt/session/`.

Background: [research-architecture](../research/research-architecture.md) §1.5, D4.
