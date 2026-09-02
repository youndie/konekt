---
id: B-118
title: "The migration generator cannot see a table that lives in a jar, so the draft script has produced nothing since the tables moved into :shared:db"
status: done
priority: P3
size: S
stage: stage-m7-completeness
---

# B-118 — `generateMigrations` scans directories and the tables are in jars

`scripts/generate-migration.sh` runs `:server:generateMigrations` on the build box and brings the
draft back. Run on 2026-09-02, with nothing changed in the schema, it fails before touching a
database:

```
Execution failed for task ':server:generateMigrations'
  > A failure occurred while executing GenerateMigrationsWorker
     > URI is not hierarchical
  at MigrationGenerator.getClassesInPackage (MigrationGenerator.kt:215)
```

The plugin resolves `tablesPackage` — `io.konekt.db.tables` — through a `URLClassLoader` over the
server's **runtime classpath** and walks each hit as `File(resource.toURI())`. That works for a
class directory and throws for a `jar:` URL, and every `Table` in this repository sits in
`:shared:db`, which reaches the server's runtime classpath as a jar. The
package is found; the walk refuses it.

**It is not the Exposed `1.5.0` bump.** The same tree with `exposedPlugin = "1.4.0"` and the `1.5.0`
libraries fails on the same line — measured as the control before concluding anything — and the
generator's scan is byte-for-byte the same code in both tags. So the script has been unable to
draft since the tables left `:server` for `:shared:db`, and nothing said so: every schema change since was written
by hand, which `B-36`'s rule allows and `KonektSchemaTest` checks, so the product is fine and the
tool is dead.

## What that costs

The expand/contract drafting step is manual today anyway, so the loss is the *checklist* the
generator provided: a diff that names every column the code changed. Without it a forgotten column
is found by `KonektSchemaTest` at test time rather than by the draft at writing time — later, and
with less to say about which table.

## Options

- **Point the scan at directories.** The plugin's `classpath` is a `ConfigurableFileCollection`
  with the runtime classpath as its default; asking the same configuration for its project
  dependencies as `classes` — the directory variant every Java project exposes — and keeping the
  external jars as they are keeps the jar out of the walk. Cheapest, and entirely in `server/build.gradle.kts`.
- **Ask upstream to walk a jar.** `getClassesInPackage` could open a `jar:` URL through
  `JarURLConnection` or a `FileSystems.newFileSystem`; a multi-module project is the ordinary shape
  for a plugin whose documentation says "the project's runtime classpath". Ask before filing — a
  repository that is not ours — and file it with the one-line reproduction above.

## Acceptance criteria

- AC: `scripts/generate-migration.sh` on an unchanged schema ends with "nothing to draft" rather than
  an exception, and on a table with one added column drafts that column.
- AC: whichever option is taken, the plugin's classpath is stated in `server/build.gradle.kts` beside
  the `tablesPackage` it scans, with the reason.
- AC: the finding is recorded in [research-upstream-proposals](../research/research-upstream-proposals.md)
  if it goes upstream.

## What was done

**The first option, and then a second defect the first one uncovered.** Both measured through
`scripts/generate-migration.sh` on the build box, which is the only way the generator is run.

**1. The scan sees directories.** `server/build.gradle.kts` hands the plugin two views of the
runtime classpath joined: the project dependencies asked for as `classes` — the directory variant
every Java project exposes, so `:shared:db` arrives as `build/classes/kotlin/main` — and the
external dependencies as the jars they are, which the scan never opens but `Class.forName` on a
`Table` needs, its supertypes being Exposed's. The scan then found every table.

**2. And diffed them against nothing.** The plugin has one directory: it runs its own Flyway over
`filesystem:<fileDirectory>` in the container, then writes the draft beside what it applied. An empty
`build/generated-migrations` meant a container with no schema, and the first successful run drafted
the whole product — five files, four of them `CREATE_TABLE_SUBSCRIBER.1` to `.4`, which is
Exposed#2897's fix visibly at work on a draft nobody wanted. Staging the committed scripts into
that directory got Flyway as far as V11, whose `CREATE INDEX CONCURRENTLY` needs the second half of
the recipe `DatabaseFactory` explains — `postgresql.transactional.lock=false` — and the plugin's
Flyway cannot be given it. V11 died of its own lock timeout (55P03), which is the failure the
migration was written to prefer over a hang.

**So the generator diffs against the stand's Postgres instead.** Given `databaseUrl`, the plugin
runs no Flyway at all: it connects and diffs. The stand's database is the committed migrations
applied by the application's own Flyway with production's configuration; the script runs
`make stand-up` first, so the schema is this tree's. The address is the compose file's loopback
mapping, overridable with `-Pkonekt.draft.db=`.

## Verified

- Unchanged schema: the script ends with *nothing to draft — the Table definitions match the
  committed migrations* and no file.
- One nullable column added to `SubscriberTable`, the script run, the column reverted:

  ```
  drafted: build/generated-migrations/V20260902210726__ALTER_TABLE_SUBSCRIBER.sql
  ALTER TABLE subscriber ADD probe_column VARCHAR(8) NULL;
  ```

  One file, one statement, the column's own. The control that separated the two defects — the
  1.4.0 plugin failing on the same line as 1.5.0 — is in the item above.

## Anchors

| What | Where |
|---|---|
| The script | `scripts/generate-migration.sh` |
| The plugin's configuration, the classpath and the database it diffs against | `server/build.gradle.kts` (`exposed { migrations { … } }`) |
| The scan | Exposed `exposed-plugin-core`, `MigrationGenerator.getClassesInPackage` |
| The rule that kept the product safe meanwhile | `B-36`, `KonektSchemaTest` |
