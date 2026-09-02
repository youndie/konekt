---
id: B-02
title: "Postgres, Flyway and the Exposed plugin, including the tables petich does not create"
status: done
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

- AC ✅: `PetichStorageTest` runs a real saga through `PetichEngine` against the migrated schema and
  reads the row back through a **second** repository instance — so the assertion is about what
  Postgres holds, not about the object the engine returned.
- AC ✅: Postgres 18 in a Testcontainer, the deployment's major, migrated by the real Flyway scripts.
  No H2 anywhere.
- AC ✅, and this one earned its keep immediately: `KonektSchemaTest` asks Exposed — after Flyway has
  run — whether any DDL is still required for petich's four tables and konekt's three. That is a
  machine comparing the SQL against the Table definitions, which is the only way to check column
  types, lengths, nullability, defaults and constraint names without a third description that is
  wrong in its own way. It caught a missing table the generator had silently omitted, and a `DEFAULT`
  that had drifted.
- AC ✅ (the question this item was told to settle): `tablesPackage` is a single package root and the
  plugin does scan below it. The answer that mattered more is that the generator cannot be trusted —
  see below.
- Anchors: `server/src/main/resources/db/migration/`, `server/src/main/kotlin/io/konekt/db/`,
  `server/src/test/kotlin/io/konekt/db/`, `scripts/generate-migration.sh`.

## What this item ran into

**petich's Exposed repositories were unreachable.** All four compiled into the default package, so no
file in a named package could reference them — the module's whole purpose, unusable from any
application. Filed as [petich#8](https://github.com/youndie/petich/issues/8) and **closed the same
day in `0.1.0.8`**; the reflective bridge konekt had written is deleted and the compiler checks the
constructor again.

**The migration generator omits a table and reports success.** With two tables referencing the same
parent, one of them is simply not emitted — [JetBrains/Exposed#2897](https://github.com/JetBrains/Exposed/issues/2897).
Its filenames also collide, being stamped to the second, and Flyway refuses a set with duplicate
versions. So `generateMigrations` produces a draft in two senses, and `KonektSchemaTest` is what
makes the draft safe to use. The omission closed upstream in Exposed `1.5.0` (2026-08-26, taken here
2026-09-02); the shared version is still the generator's, and the renumbering step stays.

**`generateMigrations` can run on neither machine alone.** It needs Docker, which exists only on the
Linux box, and it writes files, which the one-way mutagen replica reverts. `scripts/generate-migration.sh`
runs it there and reads the drafts back here — and corrects
[research-stack](../research/research-stack.md) D23, which had said the task was Mac-local.

**petich declared no index its own comments ask for**, so an Exposed-driven diff proposed dropping
them ([petich#9](https://github.com/youndie/petich/issues/9)). Also closed in `0.1.0.8`, under the
same index names, so the `DROP INDEX` exemption the schema check had carried is gone and the
assertion is strict again — which matters, because while the exemption stood an index that genuinely
should have been dropped was invisible to the same check.

Background: [research-architecture](../research/research-architecture.md) §1.7,
[research-stack](../research/research-stack.md) §1.2, §1.5, §1.9, D16, D23.
