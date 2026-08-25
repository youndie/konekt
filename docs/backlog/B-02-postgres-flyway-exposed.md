---
id: B-02
title: "Postgres, Flyway and the Exposed plugin, including the tables petich does not create"
status: open
priority: P0
size: M
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-02 — Postgres, Flyway and the Exposed plugin, including the tables petich does not create

`petich-postgres` takes an Exposed `Database` and ships no driver, no connection pool, no DDL and no
migrations ([research-architecture](../research/research-architecture.md) §1.7). So the saga table,
the outbox table, the idempotency table and the scheduler table are konekt's migrations, written from
the shapes petich's repositories read, and the domain schema sits beside them.

- **The decision and its reason.** The `Table` objects are the source of truth for the schema, and
  the numbered Flyway files are **drafted** from them by the Exposed plugin's `generateMigrations`,
  then read, edited and committed. Not `SchemaUtils.create`: the saga table is the hottest row in the
  system (≈9 writes per four-step saga) and it will need index changes, and a schema applied straight
  from code turns that into an argument about code rather than a migration.
- **The generated file is a draft, never applied unread.** A differ emits the shortest SQL that makes
  two schemas equal — `DROP COLUMN`, `RENAME` — which is exactly what breaks a rolling deploy. Turning
  it into an expand/contract pair is `B-36`, and that item's check is what stops the rule from being
  review-only.
- `mavenCentral()` goes into `pluginManagement.repositories`: the Exposed plugin is published there
  and not to the Gradle Plugin Portal, and its absence reads as "plugin not found". Flyway's own
  Gradle plugin does the applying, with `flyway-database-postgresql` beside `flyway-core`.
- **`tablesPackage` is one package root**, so every `Table` in every `feature/*-server-data` module
  must sit under `io.konekt`. Whether the plugin scans recursively is checked here rather than
  assumed, and if it does not, the answer changes the package layout — which is why this item comes
  before any feature.
- **`generateMigrations` is a Mac-local task.** It writes files, and files written on the Linux
  replica are reverted on the next sync (D20). Its Testcontainers mode is what makes it usable: it
  applies the committed migrations to a throwaway Postgres and diffs against that, rather than
  against whatever a developer's local database happens to hold.
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
[research-stack](../research/research-stack.md) §1.2, §1.5, D16, D23.
