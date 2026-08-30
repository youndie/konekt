---
id: B-97
title: "The rolling check builds the previous release and can run a binary from a week ago, reporting success either way"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-97 — The one check that runs old code can run the wrong old code

`scripts/rolling-check.sh` extracts the previous release with `git archive`, builds it, and starts it
beside the current stand. Driving that flow by hand for `B-96` produced a server that answered
**404 for `/api/v1/screens/roaming`** — a route added in `B-88`, five commits before the tree being
built. Every step had reported success.

What was actually running was an image built on **26 August**, from a distribution left in
`.rolling/previous/server/build/install/` by an earlier rolling check. The sequence, all green:

1. the Mac replaces `.rolling/previous` wholesale (`rm -rf`, then `git archive | tar -x`);
2. `wsl-run ./gradlew :server:installDist -q` reports nothing, which under `-q` is what success looks
   like;
3. `docker compose ... up -d --build server-previous` reports `Healthy`;
4. the container serves a tree nobody asked for.

**The distribution's timestamp is what said so** — `08-26_20:14` on every jar in
`.rolling/previous`, beside `08-30` in the working tree. Nothing else in the chain mentions it.

## What is established and what is not

**Established.** The stale distribution happened, the image was built from it, and the container
answered as the older code. `docker image inspect` gives `built=2026-08-26`, and the 404 is the
product-level symptom.

**Not established: the mechanism.** A second attempt did not reproduce it — deleting
`server/build/install` and running the same `-q` command rebuilt the distribution correctly. So the
trigger is something about the state after the tree is *replaced*, not the command.

**The leading hypothesis, to be confirmed or refuted rather than assumed.** `git archive` writes
every file with the COMMIT's date, so a freshly extracted tree carries mtimes older than the outputs
a previous run left behind. Gradle's up-to-date checks and build cache then have every reason to
believe the existing `build/` is current. The later successful run reported `31 from cache`, which is
consistent and is not proof.

## Why this is worth an item rather than a note

The rolling check is the only thing in this repository that runs OLD code, and its entire value is
that the binary is the previous release. A rolling check that silently runs a different binary is the
same family as the two failures `CLAUDE.md` already names — the conformance kit passing when it finds
no targets, and petich completing sagas while dropping their events — where a green result and a
check that never happened are indistinguishable.

It is also worse than a vacuous pass here: the stale binary is a *plausible* old server, so the check
appears to be doing exactly its job.

## Acceptance criteria

- AC: the script fails, loudly, when the distribution it is about to package is older than the tree
  it was extracted from. A timestamp comparison is enough and needs no Gradle knowledge.
- AC: better, the script does not rely on incremental state at all — `--rerun-tasks`, or a
  `rm -rf .rolling/previous/**/build` before the build. Measure what that costs before choosing;
  a check nobody waits for is a check nobody runs.
- AC: the container is asked WHO IT IS before anything is driven through it. A route that exists only
  in the intended tree, asserted before the suite runs, turns this class of failure into one line
  instead of a puzzle. `RollingDeployTest` is where that assertion belongs.
- AC: the hypothesis above is confirmed or refuted in writing, with what was run.

## Anchors

| What | Where |
|---|---|
| The script | `scripts/rolling-check.sh` |
| The overlay it starts | `deploy/compose.rolling.yaml` |
| What it drives | `e2e/src/test/kotlin/io/konekt/e2e/RollingDeployTest.kt` |
| Where the stale distribution sat | `.rolling/previous/server/build/install/` |
| The probe that found it | `probes/view-refactor/compare.sh` |

## What was done

Three changes to `scripts/rolling-check.sh`: one removes the state the failure needed, two refuse to
proceed when the artefact is not the one the tree produced.

**The build state of the previous tree is cleared ON THE BUILD MACHINE.** The `rm -rf` at the top of
the script cannot do it, and that is the fact this item turns on: mutagen's ignore list is `build`,
`.gradle`, `.kotlin`, matched **anywhere** in the tree, so those directories exist only on the far
side and no deletion from the Mac reaches them. A tree replaced from this end meets a `build/`
belonging to whatever commit was checked last.

**An artefact must postdate its source.** The distribution is compared against the moment the tree was
extracted, and a jar older than the extraction stops the run with both timestamps printed.

**The image must carry the distribution that was just built**, compared by `sha256` — the jar on disk
against the same path inside the image, read from a throwaway container rather than by `exec` into
the running one, because an exec inside a memory-capped container is charged to that container and
this repository has already watched one kill the application it was inspecting.

`--force-recreate` was added beside `--build`: a rebuilt image is not a restarted container, and in
the original incident the container was recreated and still carried the older image.

## What was established, and what was refuted

The item filed a leading hypothesis. **It is refuted**, and so is the one that replaced it.

| Hypothesis | Experiment | Result |
|---|---|---|
| `git archive` mtimes make Gradle think stale outputs are current | replaced the tree with `074010b` (no `RoamingScreen`) over a `build/` from a tree that has it, then built | **refuted** — the marker class count in the jar went to 0, so it rebuilt correctly |
| a replication race: the flush settles before the watcher sees a wholesale replacement | replaced the tree and built in ONE command, no gap | **refuted** — marker 3, the correct tree |

So the trigger is still unknown, and the fix does not depend on knowing it: what is established is the
**precondition** — build state that no deletion from this side can reach — and both guards are about
the artefact rather than about the cause.

## A second defect, found while fixing the first

The first version of the image check compared the image's `Created` timestamp. **It was wrong, and
the disproof was on the same machine**: `konekt-server:latest` reports 26 August while demonstrably
running code built four days later, because under BuildKit an image assembled from cached layers
keeps the `Created` of the build those layers came from. A guard on that field fails correct runs and
would be switched off within a week. It was replaced by the content comparison before it ever landed.

The run that caught it also showed `docker compose up -d --build` leaving a four-day-old image in
place, which is why `--force-recreate` is there.

## Verified

**Both guards proved by their own positive control**, each with the script's own code path rather
than a copy of the logic:

- the build neutered so the previous run's artefact survives — *"the distribution predates the tree
  it was built from"*, both timestamps named, exit 1;
- the image left in place while the distribution is rebuilt for another commit — *"the image does not
  carry the distribution that was just built"*, both digests named, and the run stops before
  *driving the previous server* is ever printed.

**And the check itself, run end to end for the first time since `V12__saga_sweep_claim.sql` landed:**
`scripts/rolling-check.sh bebf4b3` — the last commit before that migration — green, `RollingDeployTest`
2 tests 0 failures. V12 leaves the running version working, which is what expand-and-contract claims
and what nothing had yet measured.

**Cost, measured rather than estimated:** 55 seconds end to end including the cleared build state,
because the Gradle build cache carries most of it. Clearing is cheap enough that detecting the
failure was never the interesting half.

## Anchors

| What | Where |
|---|---|
| The script | `scripts/rolling-check.sh` |
| The overlay | `deploy/compose.rolling.yaml` |
| What it drives | `e2e/src/test/kotlin/io/konekt/e2e/RollingDeployTest.kt` |
| The ignore list that makes the precondition | `mutagen sync list --long konekt` |
