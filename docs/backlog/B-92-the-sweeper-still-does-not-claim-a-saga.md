---
id: B-92
title: "Two sweepers still compensate the same abandoned saga; B-64 closed the money and left the race"
status: open
priority: P2
size: S
stage: stage-m7-completeness
---

# B-92 — The outcome is correct and the work is still done twice

[B-64](B-64-a-rollback-refunds-once-per-replica.md) found a purchase abandoned at its confirmation
being refunded once per running replica, and closed it at the invariant: a unique index on
`ledger_entry (order_id, kind)`, the entry written before the balance moves, and `23505` swallowed
because a second compensation is not an error. That was the right fix and it holds — the money is
correct now under any number of sweepers.

The item's own handoff says what it left:

> The sweeper still does not claim a saga — two of them still both compensate, and the second one
> now does nothing.

What "does nothing" costs is small and not zero, and it is worth naming because the next reader will
otherwise re-derive it: each replica's sweeper walks the same suspended sagas, opens the same
transactions, calls the same compensation chain, and reaches the same unique-index violation. On this
build, with one replica and a demonstration's worth of sagas, that is invisible. It stops being
invisible in the two places a reference is read from: a compensation that talks to an external system
would call it twice — the payment mock is idempotent by construction and a real PSP's `refund` is
not — and the counter of compensations `PetichEngineMetrics` exposes reports one number per replica
for one reversal, which is exactly the observability question metrik is here to answer.

- **The decision: claim the saga before compensating it, with a conditional `UPDATE` — the same
  arbitration the refresh-token rotation already uses in this repository.** Not a lock, not a leader
  election: one row, one conditional write, the loser does nothing and knows it.
- **The unique index stays.** It is the invariant and this is the optimisation; a claim that fails
  open must still land on a correct outcome.
- **The rejected alternative is a single-sweeper deployment** — one replica designated to run it.
  That trades a cheap write for a deployment rule nothing enforces, and this build has already
  learned what an unenforced deployment rule looks like from the other side
  ([B-91](B-91-a-second-replica-loses-live-updates.md)).
- This item does **not** revisit petich's engine. The claim belongs in konekt's sweeper; if it turns
  out the engine should offer one, that is a finding for
  [research-upstream-proposals](../research/research-upstream-proposals.md) and an issue, not a fork.

- AC: two sweepers racing on one suspended saga produce one compensation attempt, verified against a
  real Postgres with both on `Dispatchers.IO`, the way `B-64` verified its index.
- AC: the loser is observable — it is a counter or a log line, not silence, because a claim that
  never wins looks the same as one that never runs.
- AC: the unique-index test from `B-64` still passes unchanged.
- Anchors: `docs/backlog/B-64-a-rollback-refunds-once-per-replica.md`,
  `server/src/main/kotlin/io/konekt/Application.kt`,
  `feature/auth-server-data/src/main/kotlin/io/konekt/feature/auth/server/data/ExposedSessionRepository.kt`,
  `shared/db/src/main/resources/db/migration/`.
