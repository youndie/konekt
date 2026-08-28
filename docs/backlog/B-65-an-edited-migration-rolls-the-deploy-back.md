---
id: B-65
title: "Editing a comment in a deployed migration rolled the deploy back, and nothing before the contour noticed"
status: done
priority: P1
size: S
stage: stage-m4-proof
epic: feature-server
---

# B-65 — A comment is bytes, and Flyway checksums bytes

V11 shipped in `v0.1.9`… except it did not. The tag built, the image published, its `verify` job
pulled the tag back and ran the whole stand green, and the deploy then sat for ten minutes and rolled
back to `v0.1.8`.

What changed between the tags was **one comment**. `V11__ledger_entry_one_movement_per_kind.sql`
had been deployed in `v0.1.8`; afterwards its comment was corrected — the recovery procedure it
described had since been measured and was wrong — and no SQL was touched.

Flyway checksums the **whole file**. The recorded number for version 11 on the contour was
`194226170`; the corrected file computes `593405495`. The `migrate` init container refused on
validation and crash-looped.

## Why nothing caught it

Every gate this repository has looks at what a migration *produces*: `KonektSchemaTest` asks Exposed
whether any DDL is still required, `MigrationFilesTest` reads the names, `ExpandAndContractTest`
reads the markers, `ConcurrentIndexTest` runs the recipe. All of them are satisfied by a file whose
bytes moved, because none of them is about the bytes — and the checksum is the one property of a
migration that is about nothing else.

The stand cannot see it either, and this is the part worth keeping. The stand builds its database
**from empty**, so every migration is new there and no checksum is ever compared. The one machine
that could have caught this is a contour that has already run the version — which is to say, the
deploy, which is where it was caught.

## How it presents

Not as "you edited a migration". As:

```
resource Deployment/konekt/konekt not ready. status: InProgress, message: Available: 0/1
context deadline exceeded
```

A readiness timeout, ten minutes after the push, on a release that rolls itself back — the same
symptom as a slow image pull, a bad probe, or a crash in the application. The init container's
`BackOff` is one line among twenty in the namespace's events.

## What was done

- **The migration was restored byte for byte** from `v0.1.8` and verified: `194226170` again.
- **`shared/db/src/test/resources/applied-migrations.checksums`** records the number for every
  migration file. Adding a line is routine; changing one is the defect, and the file says so at the
  top, where somebody about to regenerate it is looking.
- **`AppliedMigrationsAreImmutableTest`** compares the lock against the files, in both directions — a
  changed file and a lock line naming a file that is gone, because a rename leaves the contour's row
  behind exactly as an edit does.
- **`MigrationChecksumOracleTest`** runs real Flyway over all eleven migrations on a private schema in
  the Postgres container and asserts the numbers it writes into `flyway_schema_history` are the
  numbers in the lock. Without it the guard would be checking its own arithmetic against itself, and a
  Flyway upgrade that hashed differently would leave every number wrong at once, silently.
- Both were proved by mutation: appending a comment line to V11 fails both, and the oracle names
  Flyway's own figure for the mutated file rather than a recomputation of it.

Three of the eleven locked numbers — V9, V10 and V11 — were checked against the rows actually stored
on the test contour before any of this ran, so the lock did not start from a self-consistent guess.

## The recovery, if it happens again

The pod will not boot until the contour's history agrees with the file. Either restore the file, or
`flyway repair` against every contour that ran the version. A **new** migration is nearly always
cheaper than either.

## Anchors

| What | Where |
|---|---|
| The lock | `shared/db/src/test/resources/applied-migrations.checksums` |
| The guard | `shared/db/src/test/kotlin/io/konekt/db/AppliedMigrationsAreImmutableTest.kt` |
| Its oracle | `shared/db/src/test/kotlin/io/konekt/db/MigrationChecksumOracleTest.kt` |
| The migration | `shared/db/src/main/resources/db/migration/V11__ledger_entry_one_movement_per_kind.sql` |
| Where it is written down for an operator | `docs/services/konekt-server.md` §8 |
