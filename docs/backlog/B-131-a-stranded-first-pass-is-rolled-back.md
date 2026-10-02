---
id: B-131
title: "A stranded first pass is rolled back rather than carried forward"
status: open
priority: P3
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

**Read, not reproduced.** A second run of either throws on the duplicate, petich compensates the
member that threw, and the undo goes by the order — so the hold comes back and the change is
cancelled. The money and the tariff end right; what goes wrong is that a purchase or a change the
subscriber started, and that only lost its process, ends `compensated` instead of reaching its
confirmation. It is the safe direction of the rule petich's README states first — a member's body
must be idempotent — and the reason this is P3.

- AC: a test that kills the process after the hold and before the suspension's write, has the
  stranded queue re-drive it, and finds the order waiting for its confirmation with one `HOLD`, one
  pending entitlement and the balance down once — red before the change.
- AC: the same for a tariff change.
- Anchors: `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseSteps.kt`,
  `server/src/main/kotlin/io/konekt/tariff/TariffSteps.kt`
