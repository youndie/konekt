---
id: B-133
title: "saga_sweep_claim is written by nothing and still in the schema"
status: done
priority: P3
size: S
stage: stage-m7-completeness
---

# B-133 — drop `saga_sweep_claim` one release after its last writer

[B-132](B-132-claimed-sweep-is-a-second-claim.md) removed `ClaimedSweep`, the only code that wrote
`saga_sweep_claim` (`shared/db/src/main/resources/db/migration/V12__saga_sweep_claim.sql`). The table
stayed, because during the roll that ships B-132 the pods still on the previous release insert into
it — a drop in the same release would fail their sweeps. It is the contract half of a pair whose
expand half is the release that stopped writing it.

- AC: a migration drops `saga_sweep_claim`, carrying `-- contract: expanded in V<n>` with `V<n>` the
  newest migration of the release that carried B-132 — B-132 has none of its own, and
  `ExpandAndContractTest` asks only that the named one exist and come earlier — and the test passes.
- AC: it ships in a release after the one that carried B-132, not with it.

## Findings — 2026-10-06

- **Done.** `V17__drop_saga_sweep_claim.sql` drops the table with `-- contract: expanded in V16`: B-132
  first shipped in v0.1.45 (its merge `d09d185` is in no earlier tag), and V16 is that release's newest
  migration. This merges after v0.1.45 and ships with the next tag, which is the second AC.
- `SagaSweepClaimIsGoneTest` asserts the migrated schema has no such table; with the `DROP` replaced by
  `SELECT 1` it is red. With the marker removed, `ExpandAndContractTest` is red. Checksum locked.
- No Exposed table or Kotlin reference to it remained — B-132 took them — so `KonektSchemaTest` has
  nothing to drop from its list.
