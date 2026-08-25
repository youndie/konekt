---
id: B-34
title: "One error contract: Result out of use cases, StatusPages into status codes"
status: open
priority: P1
size: S
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-34 — One error contract: Result out of use cases, StatusPages into status codes

A use case returns `Result`, a route answers `.getOrThrow()`, and one `StatusPages` block maps domain
exceptions onto status codes. Deciding this once is what keeps a route from growing its own
`onFailure` branch per feature, and it is a prerequisite for the API layer of the documentation, where
the auth tier and the error codes of an endpoint are columns rather than prose.

- **The decision and its reason.** `suspendRunCatching` rather than `runCatching`, written once in a
  shared place. Plain `runCatching` swallows `CancellationException`, and the symptom is a request
  that will not stop rather than an error anybody attributes to it.
- **Do not wrap a `Result` in a `Result`.** A repository that receives one from below unwraps it and
  throws a domain exception; the use case's `suspendRunCatching` catches that. A `failure` nested
  inside a `success` travels past the error handler in silence.
- A route writes `.onFailure` only for a specific error that needs its own body — "the provider
  declined" is one, and it is the rollback screen of `B-11`.
- **The auth tier of a route is chosen and written down, not inherited from indentation.** A route
  outside `authenticate { }` has a tier too, and it is "open". Per-user ownership is checked in the use
  case beside the principal, not in the route, and it has its own test: someone else's id answers 404
  rather than 403, so the API does not confirm that the object exists.
- Not covered: an error taxonomy on the wire. Status codes plus a machine-readable code field; no
  problem+json.

- AC: an unhandled domain exception from any route produces its mapped status and body, asserted per
  exception type.
- AC: cancelling a request cancels the work — a test asserts the coroutine actually stops.
- AC: a request for another subscriber's order answers 404.
- Anchors: `server/src/main/kotlin/io/konekt/http/StatusPages.kt`,
  `shared/domain/src/commonMain/kotlin/io/konekt/domain/SuspendRunCatching.kt`.

Background: [research-stack](../research/research-stack.md) D14.
