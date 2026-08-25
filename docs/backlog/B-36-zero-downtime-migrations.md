---
id: B-36
title: "Expand and contract: a migration is compatible with the code already running"
status: done
priority: P0
size: M
stage: stage-m0-wire
blocked_by: [B-02]
---

# B-36 — Expand and contract: a migration is compatible with the code already running

During a rolling deploy both versions of the code run against one schema, and there is no moment at
which only one of them does. A migration that assumes otherwise works in staging, where a single
process is replaced instantly, and takes the service down in production, where two are not.

This is made sharper by the generator: `generateMigrations` compares `Table` definitions with the
live schema and emits the **shortest** SQL that makes them equal — `DROP COLUMN`, `RENAME`,
`ALTER … TYPE` — which is exactly the set that breaks a roll
([research-stack](../research/research-stack.md) §1.5). The generated file is a draft, not a
migration.

- **The decision and its reason.** Every schema change is a pair of releases. Expand: add nullable,
  dual-write, backfill. Contract, one release later: switch reads, drop the old. The rule is written
  as a table in [research-stack](../research/research-stack.md) D22 with a row per kind of change, so
  it is looked up rather than re-derived at the moment somebody is in a hurry.
- **A concurrent index needs two settings, not one.** `V<n>__<desc>.sql.conf` with
  `executeInTransaction=false`, and `flyway.postgresql.transactional.lock=false` — without the second,
  Flyway's transactional lock deadlocks against the index build and the migration **hangs** rather
  than failing. During a deploy a hang reads as a slow rollout, so the migration step carries a
  deadline shorter than the deploy's, to convert it into a failure that names itself.
- **Every DDL statement carries a `lock_timeout`.** An `ALTER TABLE` waiting for a lock queues every
  reader behind it, and a blocked table is downtime whatever the deploy is doing.
- **Migrations run separately from and before the application**, from the same image with a
  migrate-only switch, so the schema is current when the first new process starts and two processes
  never race to migrate.
- The rejected alternative is enforcing the rule by review. It holds until the week somebody is in a
  hurry, and its failure is invisible until a deploy.
- Not covered: data migrations large enough to need batching. The first one becomes a background job
  with a resumable cursor, not a Flyway script.

- AC OK, **in a stronger form than written**: a migration that takes something away fails a gate
  **before it is ever run**, rather than failing a stand afterwards. `ExpandAndContractTest` refuses
  `DROP COLUMN`, `DROP TABLE`, `RENAME`, `ALTER … TYPE` and `SET NOT NULL` in any migration that does
  not carry `-- contract: expanded in V<n>`, and then checks that the named expand exists and came
  earlier — so a pair written backwards fails too. The escape hatch is deliberate and was tested in
  both directions: without it the rule is unusable and gets deleted the first week somebody needs it.
- AC OK: every migration bounds how long it waits for a lock, enforced by the same gate. All eight
  existing ones gained `SET lock_timeout = '3s'`, which is safe to do now and would be a checksum
  change on a deployed schema later.
- AC OK: an index migration completes without blocking a concurrent writer, measured with a live
  writer against a real Postgres 18 — **and with its own control in the same run**, because a
  measurement of one variant is only "writes happened". The first version asserted that a plain
  `CREATE INDEX` blocks for at least 200ms; the Linux box did it in 148, which says something about
  the box and nothing about the index. The claim is now relative: the plain build must block at least
  four times longer than the concurrent one. Stable across three consecutive runs.
- AC **moved to `B-35`**, not carried: running the **previous release's image** against the new schema
  is a requirement on the stand, not work this item can do — there is no stand and no previous
  release. It is written into that item rather than left as a remainder here, because it will never
  become true from this side.
**One thing the gate cannot do, said plainly.** It reads the migration, not the code: it knows a
column is being dropped and cannot know whether the previous release still reads it. That is exactly
what AC1 is for, and it is why the gate demands a marker naming the expand rather than trying to
decide for itself — the marker is a person asserting the thing a machine here cannot check, at the
moment they are best placed to know it.

- Anchors: `shared/db/src/main/resources/db/migration/`,
  `shared/db/src/test/kotlin/io/konekt/db/ExpandAndContractTest.kt`,
  `shared/db/src/test/kotlin/io/konekt/db/ConcurrentIndexTest.kt`,
  `shared/db/src/main/kotlin/io/konekt/db/DatabaseFactory.kt`.

Background: [research-stack](../research/research-stack.md) D22, §1.5, §1.6, Risk 10, Risk 11.
