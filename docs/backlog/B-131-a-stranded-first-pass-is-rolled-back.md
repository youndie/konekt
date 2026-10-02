---
id: B-131
title: "A stranded first pass is rolled back rather than carried forward"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-131 — the members before a suspension are not safe to run twice

`B-130` switched the sweeper's stranded queue on, so a saga whose process died between two of its
writes is now re-driven from the member it died in. `Provision` was made safe to run again for it.
The members that run before a suspension were not, and each writes a plain insert under a unique key:

- `HoldFunds` — `hold` writes `HOLD` under `idx_ledger_entry_order_id_kind`, then `createPending`
  writes the entitlement under `uq_entitlement_order_id`
  (`feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedPurchaseRepositories.kt`);
- `RecordTariffChange` — `record` writes the change under its unique `change_id`
  (`server/src/main/kotlin/io/konekt/tariff/TariffSteps.kt`).

**Read, not reproduced, when it was opened.** A second run of either throws on the duplicate, petich
compensates the member that threw, and the undo goes by the order — so the hold comes back and the
change is cancelled. The money and the tariff end right; what goes wrong is that a purchase or a
change the subscriber started, and that only lost its process, ends `compensated` instead of reaching
its confirmation. It is the safe direction of the rule petich's README states first — a member's body
must be idempotent — and the reason this was P3. **The money part was wrong**, which is why it is P2
now; see the findings.

- AC: a test that kills the process after the hold and before the suspension's write, has the
  stranded queue re-drive it, and finds the order waiting for its confirmation with one `HOLD`, one
  pending entitlement and the balance down once — red before the change.
- AC: the same for a tariff change.
- Anchors: `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseSteps.kt`,
  `server/src/main/kotlin/io/konekt/tariff/TariffSteps.kt`

## Findings

Through the real engine (petich `0.4.0.120`) and a real Postgres, before anything was changed:
`StrandedFirstPassTest` and `StrandedTariffChangeTest`. Each lets the first pass commit its writes,
kills the process at the write that would have parked the saga — an `Error`, which the engine does
not catch — and has a second engine's sweeper take the PROCESSING row two minutes later.

**Both readings are true as written.** With the balance at $50 and the plan at $15, the re-run `hold`
hit the unique index, `HoldFunds` threw, and the order ended `compensated` with the money back. The
tariff change ended `compensated` with the row `cancelled`.

**On a balance that covers the price once, the money did not end right.** At $20, the re-run asks the
database to take $15 again; the WHERE clause that refuses an overdraft refuses it, `hold` answers
`false`, and `HoldFunds` reads that as a balance too low: it records an `insufficient_funds` decline
and REFUSES. petich does not undo a member that refuses — it reported its outcome, so the rollback
starts below it — and `HoldFunds` is the first member that acts. Read off the old code in the same
test: the order `rejected`, the balance at $5, one `HOLD` and no `RELEASE`, the entitlement `pending`,
nothing announced. The saga is terminal, so no queue of the sweeper ever looks at it again: $15 held
for good under an order that says it was refused.

**The sweep found a fourth plain insert, `recordDecline`.** A refusal records its reason under
`(order_id, kind)` and then ends the saga. A process that dies between the two leaves the saga
PROCESSING; the re-run refuses again, the second `DECLINE` throws, and the engine turns the throw into
a fault — so a purchase refused for its balance ended `compensated`, which the result screen states as
a rollback rather than as a refusal it can offer `Top up` for (reproduced, `StrandedFirstPassTest`).
`recordDecline` is also reached from `Provision` and `CollectFunds` after a provider's decline; there
the outcome is `compensated` either way, and only the reason the engine logs differs.

### Every member of the three sagas, against the same question

| Saga | Member | Writes under a unique key | Before | Now |
|---|---|---|---|---|
| purchase | `ValidatePurchase` | `recordDecline` | plain insert | kept once |
| purchase | `HoldFunds` | `hold`, `recordDecline`, `createPending` | plain inserts | `hold` answers `true` for an order already held; the other two keep the first row |
| purchase | `Provision` | `recordDecline`, `capture`, the home grant, the roaming grant | `capture` and the grant fixed by `B-130`; roaming already `insertIgnore` | `recordDecline` kept once |
| purchase | undo of each | `release`, `cancel`, the revokes | by the order, idempotent (`B-64`, `B-130`) | unchanged |
| top-up | `ValidateTopUp` | nothing | — | — |
| top-up | `CollectFunds` | `recordDecline`, `credit` | `credit` swallows the duplicate (`B-64`) | `recordDecline` kept once |
| top-up | undo | `debit` | guarded by the ledger, idempotent | unchanged |
| tariff | `ValidateTariffChange` | nothing | — | — |
| tariff | `RecordTariffChange` | `record` | plain insert | keeps the first row |
| tariff | `ApplyTariffChange` and both undos | `apply`, `cancel` | an UPDATE from `pending` only | unchanged |

The announcements write nothing of their own: an event goes into the outbox in the transaction that
moves the saga, so a pass that died before that write left no event to repeat.

### What changed

- `hold` answers `true` for an order already held. A second `HOLD` under the order fails on the unique
  index and rolls back the balance move with it, and that violation is swallowed as `release`'s is; a
  refusal from the WHERE clause is asked of the ledger before it is believed, after the UPDATE, so a
  hold of the same order committing meanwhile is seen.
- `createPending` and `TariffChanges.record` are `insertIgnore` under their unique keys; the row
  already there is keyed by the same saga.
- `recordDecline` swallows the duplicate the way `release`, `credit` and `capture` do, and the first
  reason stays.
- The ports say each of these is once per order or change, and `HoldFunds` and `RecordTariffChange`
  say they are run again when they never got to suspend.

### Mutations, each against the finished change

| Mutation | Caught by |
|---|---|
| `hold` rethrows the duplicate `HOLD` | `a hold whose process died before the confirmation is carried to it once` |
| `hold` believes the WHERE clause without asking the ledger | `a hold that left too little for a second is not refused on its re-run` |
| `createPending` a plain insert again | both hold tests |
| `recordDecline` a plain insert again | `a refusal whose process died is still a refusal` |
| `record` a plain insert again | `a change whose process died before the confirmation is carried to it` |

Each broke only the tests named, in a run of the whole module (`:feature:purchase-server-data:test`,
67) or of `io.konekt.tariff`.

### Not covered, and why

- **Two passes holding the same order at once.** That needs a first pass that is alive past
  `STRANDED_AFTER` — four times the longest member — and the sweeper's re-run beside it. The two
  guards in `hold` are written for it (the violation, and the ledger asked after the UPDATE has waited
  on the row lock), and both are exercised by the sequential tests; the race itself is read, not run.
- **A refusal that would not be made the second time.** A re-run asks the balance again, so a top-up
  landing in the two minutes before the re-drive lets a purchase refused on its first pass go ahead,
  with the first pass's `DECLINE` beside it. The money is right and the order is the subscriber's to
  confirm. `PurchaseResultScreen` reads a decline only for an order that ended `rejected` or
  `compensated`, so the stale code shows only if that order is then rolled back too — as the
  rollback's reason, where it would read `insufficient_funds`. That takes a death, a top-up inside two
  minutes and an abandoned confirmation together; written down rather than built for.
- **The provider is asked to settle twice on a re-run**, as in `B-130`.

## Anchors

| What | Where |
|---|---|
| The hold, the entitlement, the decline | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedPurchaseRepositories.kt` |
| The ports | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchasePorts.kt`, `server/src/main/kotlin/io/konekt/tariff/TariffPorts.kt` |
| The members | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseSteps.kt`, `server/src/main/kotlin/io/konekt/tariff/TariffSteps.kt` |
| The change log | `server/src/main/kotlin/io/konekt/tariff/TariffData.kt` |
| The tests | `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/StrandedFirstPassTest.kt`, `server/src/test/kotlin/io/konekt/tariff/StrandedTariffChangeTest.kt` |
