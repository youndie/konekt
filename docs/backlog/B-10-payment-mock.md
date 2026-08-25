---
id: B-10
title: "A payment mock that can refuse and can be slow"
status: done
priority: P1
size: S
stage: stage-m1-money
blocked_by: [B-08]
---

# B-10 — A payment mock that can refuse and can be slow

The canvas draws four purchase states, and one of them is the rollback. A mock that always succeeds
can draw three of them. The demonstration's payload is compensation, and compensation cannot be shown
without a refusal on demand.

- **The decision and its reason.** In-process, behind the interface a real gateway integration would
  implement, with a configuration switch for always-succeed / refuse / delay. The interface boundary
  is what keeps the swap honest; a separate mock process would add operational surface and show
  nothing extra.
- The rejected alternative is a random failure rate. A demo that fails one time in ten is a demo that
  fails during the demo and works during the rehearsal.
- Not covered: any real card handling. Nothing resembling a card number enters this build.

- AC OK: with the mode at `decline`, a confirmed purchase ends `compensated`, the balance is exactly
  back where it was, the entitlement is cancelled, and the response carries the provider's own words.
- AC OK: with a delay set, the purchase still completes **and the test asserts it actually took the
  time**. Without that second assertion the test passes on a mock that ignores its delay, and the
  canvas's "processing" frame would have nothing behind it.
- Also done: the decline reason survives the request that caused it. petich carries a `Compensate`
  reason to its metrics and persists none, and the compensating step — the hold — has no way to know
  why it is being undone. So the step that *learned* the reason writes it down, as a zero-sum ledger
  entry whose purpose is to carry a sentence. A subscriber who closed the app and came back is still
  told why.
- Anchors: `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/MockPaymentGateway.kt`,
  `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PaymentGateway.kt`,
  `shared/db/src/main/resources/db/migration/V6__ledger_note.sql`,
  `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/PaymentDeclineTest.kt`.

## The contradiction between the design and the engine

The canvas tells the subscriber a settlement *"usually takes under 15 seconds"*. petich's default
`EXECUTION` phase timeout is **10**. So the copy on the screen describes a provider the engine would
cancel — and the cancellation would not look like a timeout to anybody: the saga compensates, the
subscriber sees a rollback, and nothing in the logs says the provider was still working.

Raised to 30 seconds rather than the copy lowered. Fifteen seconds is what a real card network can
take, and a timeout that fires before the provider has answered turns a slow approval into a rollback
nobody asked for — the one failure mode in this flow that costs a sale and looks like a decline.

It is the kind of contradiction that only exists once both halves are built: reading the canvas does
not surface it, and reading petich does not either.

## Why settling lives inside the provisioning step

Five interceptors would have been the obvious shape. It stays at four (D5), and not only for the eight
extra database writes: splitting settle from provision buys a rollback point *between a captured
payment and an inactive package*, which is a state nobody wants to be able to reach. From the
product's side they are one thing — make it real.

Background: [research-architecture](../research/research-architecture.md) D10.
