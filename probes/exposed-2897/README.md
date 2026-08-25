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
`VersionFormat`, and `-Psep` the plugin's `fileSeparator`. Each run starts its own Postgres 18
container and therefore diffs against an empty database.

`-Psep` is not decoration: the version and the separator interact. Flyway reads a version up to the
**first** separator, so an index appended with the same character the separator uses disappears into
the description.

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

## Verifying a proposed fix

[`verify-a-patch.sh`](verify-a-patch.sh) builds a branch of the plugin into `mavenLocal` and points
the probe at it. It carries three things about Exposed's own build that each cost a run to find —
its Gradle refuses JDK 25 and says so as a bare `25.0.3`, it needs a JDK 17 toolchain that is not
installed, and the plugin modules version themselves independently of the root property.

Used on [PR #2898](https://github.com/JetBrains/Exposed/pull/2898) on 2026-08-25. With it applied,
all four shapes come out complete and Flyway 13.3.0 applies every one — 3 files for `p3`, 4 for
`p4`, three distinct versions for `pflat`. The one case where it does not hold is `-Psep=_`, where
the index is written with the separator's own character and Flyway collapses the versions again;
reported in the issue as a measurement rather than as a request, since the patch is not ours.
