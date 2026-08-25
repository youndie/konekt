---
id: B-34
title: "One error contract: Result out of use cases, StatusPages into status codes"
status: wip
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

- AC ✅: every refusal is driven through a real route and asserted per type — six of them, with the
  status, the machine-readable code and a non-empty message. `everyRefusalIsCovered` compares the
  exercised set against `KonektException::class.sealedSubclasses`, so a refusal added to the domain
  and not exercised here fails.
- AC ✅: `SuspendRunCatchingTest` cancels a real job and asserts the line after the block never ran.
  A test that merely throws `CancellationException` and catches it would pass against plain
  `runCatching`; this one does not, which is the whole difference.
- AC ⏳ **carried to `B-06`**: "another subscriber's order answers 404" needs a session and an
  owner-scoped resource, and neither exists yet. It is an auth-tier rule and it belongs to the first
  feature that has an owner — written down here rather than quietly dropped.
- Also done: the `when` in `httpStatus()` has no `else`, so a refusal added to the sealed hierarchy
  and not mapped **fails to compile**. A lookup table would have let it fall through to 500, which is
  the shape of failure a client reports as "it just errors".
- Also done: an unexpected exception is logged in full and answered with nothing. Its message is
  written for whoever wrote the code — table names, identifiers, sometimes a query — and a subscriber
  is not that reader. `ErrorContractTest` asserts the leak does not happen by looking for the words.
- Also done: `RunCatchingUsageTest` refuses plain `runCatching` anywhere in the server or the shared
  modules. Coarse on purpose — `runCatching` in non-suspending code is fine and this refuses it
  anyway, because the alternative is parsing Kotlin to tell them apart, and a rule slightly too
  strict and always right beats one that is exact and sometimes silent.
- Anchors: `server/src/main/kotlin/io/konekt/http/StatusPages.kt`,
  `shared/domain/src/commonMain/kotlin/io/konekt/domain/SuspendRunCatching.kt`,
  `shared/domain/src/commonMain/kotlin/io/konekt/domain/KonektException.kt`,
  `server/src/test/kotlin/io/konekt/http/`.

Background: [research-stack](../research/research-stack.md) D14.
