---
id: B-118
title: "The migration generator cannot see a table that lives in a jar, so the draft script has produced nothing since the tables moved into feature modules"
status: open
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
class directory and throws for a `jar:` URL, and every `Table` in this repository sits in a feature
module (`feature/*-server-data`), which reaches the server's runtime classpath as a jar. The
package is found; the walk refuses it.

**It is not the Exposed `1.5.0` bump.** The same tree with `exposedPlugin = "1.4.0"` and the `1.5.0`
libraries fails on the same line — measured as the control before concluding anything — and the
generator's scan is byte-for-byte the same code in both tags. So the script has been unable to
draft since the tables left `:server`, and nothing said so: every schema change since was written
by hand, which `B-36`'s rule allows and `KonektSchemaTest` checks, so the product is fine and the
tool is dead.

## What that costs

The expand/contract drafting step is manual today anyway, so the loss is the *checklist* the
generator provided: a diff that names every column the code changed. Without it a forgotten column
is found by `KonektSchemaTest` at test time rather than by the draft at writing time — later, and
with less to say about which table.

## Options

- **Point the scan at directories.** The plugin's `classpath` is a `ConfigurableFileCollection`
  with the runtime classpath as its default; setting it to the feature modules' class output
  (`sourceSets.main.output` of each `*-server-data` project) plus their compile dependencies keeps
  the jar out of the walk. Cheapest, and entirely in `server/build.gradle.kts`.
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

## Anchors

| What | Where |
|---|---|
| The script | `scripts/generate-migration.sh` |
| The plugin's configuration | `server/build.gradle.kts` (`exposed { migrations { … } }`) |
| The scan | Exposed `exposed-plugin-core`, `MigrationGenerator.getClassesInPackage` |
| The rule that kept the product safe meanwhile | `B-36`, `KonektSchemaTest` |
