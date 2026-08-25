---
id: B-36
title: "Expand and contract: a migration is compatible with the code already running"
status: open
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

- AC: the end-to-end stand runs the **previous** release's server image against the **new** schema and
  passes — the state a rolling deploy actually passes through.
- AC: a migration that would drop a column still read by the previous release fails that check.
- AC: an index migration completes without blocking a concurrent writer, verified by a test that
  writes during it.
- Anchors: `server/src/main/resources/db/migration/`, `deploy/compose.yaml`,
  `e2e/src/test/kotlin/io/konekt/e2e/RollingSchemaTest.kt`.

Background: [research-stack](../research/research-stack.md) D22, §1.5, §1.6, Risk 10, Risk 11.
