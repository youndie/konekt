---
id: B-08
title: "The purchase saga: four interceptors, with the confirmation as a suspend"
status: done
priority: P0
size: L
stage: stage-m1-money
epic: feature-buy-package
blocked_by: [B-02]
---

# B-08 — The purchase saga: four interceptors, with the confirmation as a suspend

Buying a package is the operation the whole build exists to show end to end. petich's phases are
fixed — `ENRICHMENT → VALIDATION → AUTHORIZATION → EXECUTION → POST_PROCESSING` — and a step that
needs a human returns `InterceptorResult.Suspend(requiredAction, ttl)`, after which a sweeper rolls
the saga back exactly as a refusal would if nobody comes back.

- **The decision and its reason.** Four interceptors, not six: validation, authorization (hold, then
  suspend for confirmation), execution (charge and provision), post-processing (emit). petich's own
  measurement is ≈9 database writes for four steps against ≈17 for six, taken through
  `pg_stat_user_tables`, and the saga table is written at every step boundary by design.
- The rejected alternative splits hold and confirm into separate interceptors for readability. It
  costs about eight extra writes on the most frequent operation in the product to make one
  interceptor easier to read.
- Not covered: what the TTL should be. That is open question 2 in the research and is answered here,
  in writing, with the number that was chosen.

- AC OK: a confirmed purchase leaves the balance debited exactly once, the entitlement `active` and
  the order `completed`, with `purchase.completed` in the outbox.
- AC OK: a purchase nobody confirms is swept after its TTL, the balance returns to exactly where it
  was, the entitlement is `cancelled` and the order reads **`compensated`**. A sweep *inside* the
  window is asserted to do nothing — without that, a sweeper that rolled everything back regardless
  of time would pass the rest of the test on its own.
- AC OK, and this is where the TTL question is answered: **five minutes**, set on the step rather than
  on the engine. It is the same order as the one-time code a confirmation usually involves, and it
  bounds how long the subscriber's own money sits held on a purchase they walked away from — long
  enough to read a message and type six digits, short enough that an abandoned tab does not cost them
  their balance for an hour.
- Also asserted: a plan that is not on sale is `rejected` with the balance untouched (the refusal
  happens in VALIDATION, before the hold, so there is nothing to reverse), a purchase beyond the
  balance is refused by the `WHERE` clause rather than by a read-then-check, and somebody else's order
  answers `NotFound` rather than `Forbidden`.
- Anchors: `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseInterceptors.kt`,
  `feature/purchase-server-data/`, `shared/db/src/main/resources/db/migration/V5__purchase.sql`,
  `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/PurchaseSagaTest.kt`.

## Three things this cost, all of them silent

**`runTest` cancels an interceptor that does real I/O.** It runs on a virtual clock, so a coroutine
that suspends has time skipped forward for it — and the engine wraps every interceptor in
`withTimeout(phaseTimeout)`. The first database call inside a step therefore jumps past the timeout
and the step is cancelled, after which the saga compensates. What a test sees is a purchase that
rolled itself back for no reason it can name: petich swallows the cancellation into the compensation,
so nothing is logged. A test whose subject does real I/O inside somebody else's `withTimeout` needs a
real clock, and these use `runBlocking`.

**Compensation only walks back through steps that actually ran.** The reversal announcement was on the
POST_PROCESSING step, which a purchase abandoned at the confirmation never reaches — so the one case
the event exists for was the one case it never fired. It belongs on the step whose work is being
undone, which is the hold. A test caught it; reading the code would not have.

**Two Gradle projects may not share a simple name.** `:shared:server` beside `:server` produced a
circular dependency between `:server:compileKotlin` and `:server:jar` — an error naming neither the
collision nor the other module. It is `:shared:server-common` now.

Also, the column-shadowing trap the auth repository has a comment about was fallen into again two days
later, in this feature's test seed: inside `Table.insert { }` the table is the receiver, so a bare
name resolves to the COLUMN and Exposed emits `VALUES (?, account.subscriber_id, ...)`. A parameter
wins that resolution and a class property does not, which is why it bites in a test and not in a
repository.

Background: [research-architecture](../research/research-architecture.md) §1.7, D5, open question 2.
