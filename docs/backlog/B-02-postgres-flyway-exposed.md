---
id: B-02
title: "Postgres, Flyway and Exposed, including the tables petich does not create"
status: open
priority: P0
size: M
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-02 — Postgres, Flyway and Exposed, including the tables petich does not create

`petich-postgres` takes an Exposed `Database` and ships no driver, no connection pool, no DDL and no
migrations ([research-architecture](../research/research-architecture.md) §1.7). So the saga table,
the outbox table, the idempotency table and the scheduler table are konekt's migrations, written from
the shapes petich's repositories read, and the domain schema sits beside them.

- **The decision and its reason.** Flyway with numbered SQL migrations rather than Exposed's
  `SchemaUtils.create`. The saga table is the hottest row in the system (≈9 writes per four-step saga)
  and it will need index changes; a generated schema turns that into an argument about code rather
  than a migration.
- **Every module that touches Exposed is `kotlin("jvm")`, not multiplatform.** `exposed-core:1.4.0`
  publishes only `apiElements`/`runtimeElements` at `platform.type = jvm` and no common metadata
  variant ([research-stack](../research/research-stack.md) §1.2), which is why the data layer is the
  one layer of a feature that cannot be common code.
- The rejected alternative is letting Exposed create the tables in development and writing migrations
  later. Later is after the shapes are already wrong in somebody's database.
- Not covered: the domain tables of features not yet designed. This item covers what petich needs plus
  `subscriber`, `account`, `esim`. It also does not cover the test harness — that is `B-32`, and this
  item's acceptance runs on it.

- AC: a clean database plus `flywayMigrate` yields a schema a petich saga runs against, verified by a
  test that starts a saga and reads its row.
- AC: the migration set is replayable from empty on the Postgres major the deployment runs, not on H2.
- Anchors: `server/src/main/resources/db/migration/`, `server/src/main/kotlin/io/konekt/db/`.

Background: [research-architecture](../research/research-architecture.md) §1.7,
[research-stack](../research/research-stack.md) §1.2, D16.
