# Probe: JetBrains/Exposed#2897

Reproduction for [JetBrains/Exposed#2897](https://github.com/JetBrains/Exposed/issues/2897) —
`generateMigrations` names one file per table from the **first statement in it**, so two children of
one parent produce the same name and the earlier file is overwritten. A table disappears with exit
code 0 and a log line that agrees with what was written rather than with what was asked for.

It lives here because a comment on that issue offers to run a candidate fix through it, and the
first copy of this probe was built directly on the Linux box and did not survive — work outside a
repository is invisible.

## Running it

Both scripts run on the Linux box; the probe is built outside the mutagen tree, so it is created by
the script rather than synced.

```bash
~/.claude/bin/wsl-run "$(cat probes/exposed-2897/create-probe.sh)"
~/.claude/bin/wsl-run 'cd ~/expprobe && ./gradlew generateMigrations -Ppkg=p3 -Pout=genA'
```

`-Ppkg` selects the table shape, `-Pout` the output directory under `build/`, `-Pfmt` an optional
`VersionFormat`. Each run starts its own Postgres 18 container and therefore diffs against an empty
database.

The second script adds a Flyway 13.3.0 runner and starts a Postgres on port 55432, for checking
whether generated output can actually be applied:

```bash
~/.claude/bin/wsl-run "$(cat probes/exposed-2897/add-flyway-check.sh)"
~/.claude/bin/wsl-run 'cd ~/expprobe && ./gradlew -q flywayProbe \
    -Purl=jdbc:postgresql://localhost:55432/postgres -Pdir=$PWD/build/genD'
```

Drop the schema between runs (`docker exec fwprobe psql -U postgres -c \
'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'`) — Flyway refuses a history that does not match.

## What each shape shows

| `-Ppkg` | tables | files | result |
| --- | --- | --- | --- |
| `p3` | parent + two children | **1** | `p_child_one` lost — the reported defect |
| `p3` with `-Pfmt=MAJOR_MINOR` | the same three | 3 | all three, and Flyway applies them |
| `p4` | independent + parent + two children | **2** | `q_child_one` lost, *and* the two files share a version |
| `pflat` | three tables, no foreign keys | 3 | nothing lost; Flyway refuses the duplicate version |

Measured 2026-08-25 against Exposed and the plugin at 1.4.0, Gradle 9.7.1, JVM 25, Postgres 18,
Flyway 13.3.0. The `p4` row is the one that is not in the issue body: it is the shape that trips both
defects at once, and the reason a regression test must assert the union of `CREATE TABLE` statements
rather than anything about filenames.
