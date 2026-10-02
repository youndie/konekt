---
id: B-130
title: "An allowance has no name to be taken back by, so a grant whose answer was lost stays granted"
status: open
priority: P2
size: M
stage: stage-m7-completeness
---

# B-130 — `Provision` undoes by a record that cannot see a lost answer

`Provision.compensate` returns quietly unless the member's own step record, `Provisioned`, is there
(`feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseSteps.kt`).
The record is written after `grantAllowance` returns, and petich compensates a member whose
`execute` threw — including one whose grant committed and whose answer was then lost: a reset
connection, a deadline that fired after the commit. There the record is absent, nothing is revoked,
and the subscriber keeps the allowance while the hold's undo returns their money and cancels the
entitlement. It is the blind spot youndie/petich B-43 describes, and the one `HoldFunds` had until
it was given a name to undo by (`HoldRollbackTest`).

- **The home allowance cannot be undone by name today, and that is the whole of the item.**
  `UsageGrants.grantPlanAllowance` adds to a counter keyed by subscriber and kind; nothing in it
  knows which order added what. So the record cannot simply be dropped the way the hold's was: an
  unconditional `revokePlanAllowance` would, for a grant that never landed, take away what is left
  of an earlier plan. The record is the most this member can go on until the grant has a name.
- **The decision this asks for: key the grant by order**, the way the money ledger already is — one
  row per `(order_id, kind)` under a unique index, written in the grant's transaction, and a revoke
  by order that is a no-op when nothing is under it. The roaming branch already works this way
  (`RoamingPackages.grant` is `insertIgnore` on `order_id`, `revoke(orderId)` deletes by it), which
  is why it is the home branch alone that needs this.
- **The same key closes the forward half, which is a reading and not yet a reproduction.** petich
  re-runs a member after a version conflict on its position write, so a member's body must be
  idempotent. The roaming grant says so in its own comment; the home grant would add a second time.
  And `capture` writes a `CAPTURE` entry with a plain insert under `idx_ledger_entry_order_id_kind`,
  so a re-run would throw on the duplicate and roll back a purchase that was provisioned. Both want
  a test that drives a real re-run before anything is changed for them.
- **Not this item:** the provider's settlement. `Provision` settles before it captures, and nothing
  undoes a settlement — one synchronous `settle` with no refund is a deliberate absence
  ([reference-scope](../services/reference-scope.md)), and it is the same reason the top-up's
  `Credited` guard stays (`TopUpSagaTest`).

- AC: a test in the shape of `HoldRollbackTest` where the home grant commits and its answer is lost,
  and the rollback leaves the counters where they were before the purchase.
- AC: its control — a grant that never landed — leaves an earlier plan's allowance untouched.
- AC: the two re-run claims above are either reproduced and fixed, or written down here as wrong.
- Anchors: `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseSteps.kt`,
  `feature/usage-server-domain/src/main/kotlin/io/konekt/feature/usage/server/domain/UsageCounter.kt`,
  `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/ExposedUsageCounters.kt`,
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedPurchaseRepositories.kt`
