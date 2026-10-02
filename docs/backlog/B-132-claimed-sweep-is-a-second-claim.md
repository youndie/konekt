---
id: B-132
title: "ClaimedSweep is a second claim on top of the one petich already makes"
status: done
priority: P3
size: S
stage: stage-m7-completeness
---

# B-132 — petich claims a swept saga itself, so konekt's claim table goes

[B-92](B-92-the-sweeper-still-does-not-claim-a-saga.md) wrapped the repository the sweeper is handed
in `ClaimedSweep`: before `findExpired` returned a saga, it took a row in `saga_sweep_claim` with one
`insertIgnore`, so that of two replicas only one would roll an abandoned purchase back. On petich
`0.1.0` that was the only thing between two replicas that both read a saga before either wrote it.

petich has arbitrated both of its queues itself since its B-26 (youndie/petich#70), and the
follow-up on [konekt#48](https://github.com/youndie/konekt/issues/48) asked for the wrapper to go once
konekt took that version. konekt has been past it since `0.4.0.88` and the wrapper stayed, with a
comment saying it survived "until the purchase and tariff sagas move onto definitions too" — which
they did in #49.

## What petich does, read at the version konekt runs

`SuspendedPetichSweeper` and the engine's expiry path are unchanged between `0.4.0.112` and
`0.4.0.120` (`youndie/petich@43f7f7e!/petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt`,
`youndie/petich@d864ba6!/petich-core/src/commonMain/kotlin/Petich.kt`):

- **The expiry queue.** `sweep` hands each expired saga to `PetichEngine.expireSuspended`, which
  re-reads the row under the process's lock and, if it is still waiting and past its deadline, writes
  `PENDING_SIGNATURE → COMPENSATING` with a plain `update` whose answer is obeyed. That write is the
  claim: the row's version predicate lets one replica's through, and the other gets `Contended` and
  calls no `compensate`.
- **The stranded queue.** `sweepStuck` claims each saga it found with a version bump before running
  it, and a sweeper whose bump loses reports `onContended` and skips the saga. `ClaimedSweep` never
  claimed this queue — its `findStuck` delegated — and konekt did not run it until `B-130`.
- **A winner that dies mid-rollback** leaves the row COMPENSATING, which the stranded queue resumes.
  The lease `ClaimedSweep` carried was for that case, and it was moot: `findExpired` returns waiting
  sagas only, so a saga claimed once was never found by the expiry queue again.

## Acceptance

- The sweeper is handed petich's own store, and `ClaimedSweep`, its test and `SagaSweepClaimTable`
  are gone.
- A test puts two replicas — two engines, two sweepers — on one saga against the real Postgres, makes
  both read it before either writes, and finds one rollback (expiry) and one re-drive (stranded),
  with the other replica turned away by the claim.

## Findings

`TwoReplicasSweepTest`, in `:feature:purchase-server-data`, against the purchase saga. Each replica's
store holds its first read of the saga until the other has read it too, so both act on a copy taken
before either wrote; the test asserts `Contended` once, which only a sweeper holding a stale copy can
get. Expired purchase: one `release`, order `compensated`, balance back. Stranded purchase: one
settlement, order `completed`, balance down once. The SQL log of the run shows both claims written
against the same version and one of them matching.

**Left to timing, the race happens anyway here** — with the hold removed the test still passed,
because two sweepers started together read in lockstep. The hold is what makes it certain rather than
likely. **The arbiter itself is petich's to prove, and is:** two attempts to defeat it from the store
wrapper were arbitrated by the same row, since both replicas read one version before either wrote.
petich's `SweepClaimTest` and its `ConcurrentWritersTest` against Postgres are where it is tested.

## Not covered

- **The table.** `saga_sweep_claim` stays in the schema: during the roll that ships this, the pods
  still running the previous release insert into it. Dropping it is the contract half, one release
  later — [B-133](B-133-drop-the-saga-sweep-claim-table.md). `V12` stays, as every applied migration does.

## Anchors

| What | Where |
|---|---|
| Where the sweeper is built | `server/src/main/kotlin/io/konekt/Application.kt` (`SuspendedPetichSweeper`) |
| The race, against a real database | `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/TwoReplicasSweepTest.kt` |
| The claim that went | `youndie/konekt@396d761!/server/src/main/kotlin/io/konekt/petich/ClaimedSweep.kt` |
