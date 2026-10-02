---
id: B-130
title: "An allowance has no name to be taken back by, so a grant whose answer was lost stays granted"
status: done
priority: P2
size: M
stage: stage-m7-completeness
---

# B-130 — `Provision` undoes by a record that cannot see a lost answer

`Provision.compensate` returned quietly unless the member's own step record, `Provisioned`, was there
(`feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseSteps.kt`).
The record was written after `grantAllowance` returned, and petich compensates a member whose
`execute` threw — including one whose grant committed and whose answer was then lost: a reset
connection, a deadline that fired after the commit. There the record is absent, nothing was revoked,
and the subscriber kept the allowance while the hold's undo returned their money and cancelled the
entitlement. It is the blind spot youndie/petich B-43 describes, and the one `HoldFunds` had until it
was given a name to undo by (`HoldRollbackTest`).

- **The home allowance could not be undone by name, and that was the whole of the item.**
  `UsageGrants.grantPlanAllowance` added to a counter keyed by subscriber and kind; nothing in it
  knew which order added what. So the record could not simply be dropped the way the hold's was: an
  unconditional revoke of the plan's amounts would, for a grant that never landed, take away what was
  left of an earlier plan.
- **The decision: key the grant by order**, the way the money ledger already is — one row per
  `(order_id, kind)` under a unique key, written in the grant's transaction, and a revoke by order
  that is a no-op when nothing is under it. The roaming branch already worked this way
  (`RoamingPackages.grant` is `insertIgnore` on `order_id`, `revoke(orderId)` deletes by it).
- **The same key closes the forward half**, which was a reading when this item was opened: petich
  re-runs a member after a version conflict on its position write, so a member's body must be
  idempotent — and `capture` wrote `CAPTURE` with a plain insert under `idx_ledger_entry_order_id_kind`,
  while the home grant would add a second time.
- **Not this item:** the provider's settlement. `Provision` settles before it captures, and nothing
  undoes a settlement — one synchronous `settle` with no refund is a deliberate absence
  ([reference-scope](../services/reference-scope.md)), and it is the same reason the top-up's
  `Credited` guard stays (`TopUpSagaTest`).

## Acceptance

- A test in the shape of `HoldRollbackTest` where the home grant commits and its answer is lost, and
  the rollback leaves the counters where they were before the purchase.
- Its control — a grant that never landed — leaves an earlier plan's allowance untouched.
- The two re-run claims above are either reproduced and fixed, or written down here as wrong.

## Findings

All of it through the real engine and a real Postgres, in `ProvisionByOrderTest`, before anything was
changed for it.

**Both re-run claims are true, and the first one hid the second.** The re-run was provoked the way
the engine documents it: the write that moves the saga past `provision` finds the row touched by a
second writer — the stranded queue's own claim, a version bump — so petich re-reads it and runs
`provision` again. On the code as it stood, the second `capture` hit the unique index, the member
threw, and the engine rolled back the purchase the first run had completed: the order ended
`compensated`, the ledger held `HOLD`, `CAPTURE` and `RELEASE`, the balance was back to the full
$50, the entitlement was cancelled, `purchase.reversed` was announced — and the 20 GB the first run
granted stayed with the subscriber. The provider had settled twice. The double grant could not show
while that happened, because `capture` comes first; with `capture` alone made idempotent the same
run completed and the counters read 40960 MB, 600 minutes and 100 messages for one payment of $15.

**The main item needed the engine too.** With the grant keyed by order and the undo unconditional,
the lost-answer test still failed on petich `0.4.0.112`: the counters read twice the plan. The undo
was never called. The first member after a confirmation was outside its own rollback there — the
row kept the rollback start the suspension wrote, so `Provision` throwing compensated the hold and
not `Provision` (petich B-66, fixed in `bd0c758`). On `0.4.0.120` the same test passed with no other
change. So the item closes by the fix and the upgrade together, and the upgrade is in the same change.

**The upgrade moves where a dead confirmation goes, and the stranded queue had to be switched on.**
Since B-66 a confirmation writes PROCESSING before it runs a member. A process that dies inside
`Provision` therefore leaves a PROCESSING row with no deadline, which no expiry looks at; on
`0.4.0.112` it stayed PENDING_SIGNATURE and the expiry rolled back the hold and nothing else (read in
petich B-66 and its code, not reproduced here). konekt
ran the sweeper without `stuckAfter`, so its stranded queue was off and such a row would have stayed
for ever with the money held. It is on now, at two minutes (`MockPaymentGateway.STRANDED_AFTER`,
above the 30-second bound on a member, which petich's README sets as the floor), and a test kills
the process at that write and has a second engine's sweeper finish the purchase.

### What changed

- `capture` swallows the unique violation the way `release` and `credit` do: a second `CAPTURE` of
  the same order is one that already happened.
- `usage_grant` (`V15`): one row per `(order_id, kind)`, written by `grantPlanAllowance(orderId, …)`
  with `ON CONFLICT DO NOTHING` in the transaction that adds to the counter, and the counter moves
  only when the row is new. `revokePlanAllowance(orderId)` takes back what the rows say and marks
  them, under a `revoked_at IS NULL` predicate that is the arbiter between two compensations; a
  marked order stays spent, so a late re-run cannot grant it again.
- `Provision.compensate` cancels the entitlement and revokes both the home allowance and the roaming
  package by the order, unconditionally. `Provisioned` is no longer written and stays registered.
- petich `0.4.0.120`, and `stuckAfter` on the sweeper.

### Mutations, each against the finished change

| Mutation | Caught by |
|---|---|
| `capture` as a plain insert again | both re-run tests: the purchase ends `compensated` |
| the grant adds whether or not its row was new | both re-run tests (40960 MB), and three in `UsageGrantByOrderTest` |
| `Provision.compensate` returns before undoing anything, as with the record gate | the lost-answer test (40960 MB) |
| the revoke subtracts whether or not its mark landed | `two revokes of one order racing take it back once` |
| the revoke takes any order's unrevoked grant, not this order's | the never-landed control (0 MB — the earlier plan taken), and four others |
| petich back at `0.4.0.112` | the lost-answer test, as above |

### Not covered, and why

- **The first pass run again is rolled back rather than carried forward.** With the stranded queue
  on, a process that dies inside `HoldFunds` — after the hold, before the suspension's write — is
  re-driven, and the second `hold` hits the same unique index `capture` did. The member throws, the
  hold is released by the order, and the purchase ends `compensated`: the money is right and the
  purchase did not happen. It is the safe direction of the same contract, and it is its own item:
  [B-131](B-131-a-stranded-first-pass-is-rolled-back.md). (Wrong about the money on a balance that
  covers the price once: there the re-run refused, and the hold stayed under a rejected order —
  found and fixed in B-131.)
- **The provider is asked to settle twice on a re-run.** The mock keeps nothing; a real provider would
  be handed the order id as its idempotency key, which `settle` already receives.

## Anchors

| What | Where |
|---|---|
| The member and its undo | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseSteps.kt` |
| The port | `feature/usage-server-domain/src/main/kotlin/io/konekt/feature/usage/server/domain/UsageCounter.kt` |
| The grant by order | `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/ExposedUsageCounters.kt` |
| The capture | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedPurchaseRepositories.kt` |
| The table | `shared/db/src/main/resources/db/migration/V15__usage_grant.sql` |
| The stranded queue | `server/src/main/kotlin/io/konekt/Application.kt`, `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/MockPaymentGateway.kt` (`STRANDED_AFTER`) |
| The tests | `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/ProvisionByOrderTest.kt`, `feature/usage-server-data/src/test/kotlin/io/konekt/feature/usage/server/data/UsageGrantByOrderTest.kt` |
