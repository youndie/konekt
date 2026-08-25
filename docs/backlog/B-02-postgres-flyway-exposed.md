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
migrations (research §1.7). So the saga table, the outbox table, the idempotency table and the
scheduler table are konekt's migrations, written from the shapes petich's repositories read, and the
domain schema sits beside them.

- **The decision and its reason.** Flyway with numbered SQL migrations rather than Exposed's
  `SchemaUtils.create`. The saga table is the hottest row in the system (≈9 writes per four-step
  saga) and it will need index changes; a generated schema makes that an argument about code rather
  than a migration.
- The rejected alternative is letting Exposed create the tables in development and writing
  migrations later. Later is after the shapes are already wrong in someone's database.
- Not covered: the domain tables of features not yet designed. This item covers what petich needs
  plus `subscriber`, `account`, `esim`.

- AC: a clean database plus `flywayMigrate` yields a schema a petich saga runs against, verified by
  a test that starts a saga and reads its row.
- Anchors: `server/src/main/resources/db/migration/`, `server/src/main/kotlin/io/konekt/db/`.

Background: [research-architecture](../research/research-architecture.md) §1.7.
