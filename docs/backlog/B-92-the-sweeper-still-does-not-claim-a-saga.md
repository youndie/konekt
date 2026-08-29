---
id: B-92
title: "Two sweepers still compensate the same abandoned saga; B-64 closed the money and left the race"
status: done
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

## What was done

`ClaimedSweep` wraps the repository the sweeper is handed and claims each expired saga before
returning it. One row in `saga_sweep_claim`, one `insertIgnore` against a primary key — the database
decides, and the loser does nothing and knows it. Not a lock, not a leader election, and not an
advisory anything: the same arbitration the refresh-token rotation already uses here.

**A decorator and not a fork.** `SuspendedPetichSweeper` and `ExposedPetichRepository` are both
petich's, and nothing upstream is forked (D9). What konekt owns is *which* repository the sweeper is
given, so the claim goes in `findExpired` — the one call that decides what this replica is about to
work on.

**The claim is a LEASE**, which the item did not ask for and which the item's own reasoning requires.
A permanent claim is wrong in the case that matters: a sweeper that wins and then dies
mid-compensation would hold the saga for ever and nobody would retry it — worse than the duplicated
work this removes. Five minutes, after which it is claimable again, so the failure mode is
"compensated late" rather than "never". The unique index from
[B-64](B-64-a-rollback-refunds-once-per-replica.md) is what makes that retry harmless, which is
exactly why it stays.

**The loser is observable**: a log line naming both numbers — swept N of M, K taken by another
replica. Written only when they differ, so an ordinary sweep stays quiet, and with both numbers
because a claim that never wins looks the same as one that never runs.

## Verified

`ClaimedSweepTest`, against a real Postgres, both sweepers on `Dispatchers.IO` and started together —
the way `B-64` verified its index. Twenty sagas, two sweepers, and the assertion is that the union is
exactly twenty with no id twice. **Proved by mutation**: a claim that always succeeds returns all
twenty to both, and takes a live claim from its holder.

The unique-index test from `B-64` passes unchanged; `./gradlew check` green, 34 e2e green.

### The test harness truncated a list somebody typed

`PostgresHarness.truncateAll()` was thirteen table names written out by hand, and by the time this
item needed a fourteenth the list was already **three short**: `tariff_change` (`B-21`),
`roaming_package` (`B-19`) and `esim_wizard_session` (`B-51`) had never been added. Nothing failed —
a test that leaves rows behind breaks the *next* one, in a way that depends on execution order, which
is the hardest failure here to attribute and the easiest to write off as flakiness.

It now asks `information_schema` for every base table in the schema except Flyway's own history, with
a check that the answer is not empty — an empty truncation would leave every later test dirty and
would look exactly like a flaky suite.

## What is deliberately not in scope

petich's engine. The claim belongs in konekt's sweeper wiring; if it turns out the engine should offer
one, that is a finding for
[research-upstream-proposals](../research/research-upstream-proposals.md) and an issue, not a fork.

## Anchors

| What | Where |
|---|---|
| The claim | `server/src/main/kotlin/io/konekt/petich/ClaimedSweep.kt` |
| Where it is wired in | `server/src/main/kotlin/io/konekt/Application.kt` (`SuspendedPetichSweeper`) |
| The table | `shared/db/src/main/resources/db/migration/V12__saga_sweep_claim.sql`, `shared/db/.../CoreTables.kt` |
| The race, against a real database | `server/src/test/kotlin/io/konekt/petich/ClaimedSweepTest.kt` |
| The invariant it does not replace | `docs/backlog/B-64-a-rollback-refunds-once-per-replica.md` |
