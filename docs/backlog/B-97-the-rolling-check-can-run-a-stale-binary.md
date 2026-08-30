---
id: B-97
title: "The rolling check builds the previous release and can run a binary from a week ago, reporting success either way"
status: open
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
