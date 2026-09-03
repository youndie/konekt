---
id: B-119
title: "The rolling check says it belongs with a release, and the release workflow is the one thing that never runs it"
status: open
priority: P2
size: S
stage: stage-m7-completeness
---

# B-119 — The one guard for a rolling deploy runs on no deploy

`scripts/rolling-check.sh` starts the previous release beside the current one and drives both, which
is the only thing in this repository that exercises the state a rolling deploy actually passes
through: two binaries serving one schema. It is the reason every migration is rewritten as an
expand/contract pair (`B-36`), and the reason `B-97` exists at all.

The Makefile says where it belongs, and says it plainly:

> Deliberately not part of `e2e` and not part of `check`. It tears the stand down and rebuilds an old
> server, which is minutes rather than seconds, and it is the one check whose subject is a PAIR of
> versions rather than this one — **so it belongs with a release**, not with every commit.

A release is `.github/workflows/publish-image.yaml`, and it runs: the distribution, the image, a
refusal of an already-published tag, then a `verify` job that pulls what was published, brings the
stand up on it, drives the e2e walk and takes it down. Ten steps, and none of them is this one.

`rolling-check` appears in that workflow **twice, in comments** — "an image called after a branch is
one no `rolling-check` can ever point at", "`rolling-check` compares a release against itself without
knowing" — which is how it reads as machinery to anyone scanning the file, and is why this went
unnoticed. Its only invocation anywhere is `make rolling-check`, typed by a person.

## What that costs

The last three releases — `v0.1.38`, `v0.1.39`, `v0.1.40` — were published green without the pair
ever being stood up. Nothing says they are broken; the point is that the check that would have said
so did not run, and the release notes cannot distinguish "the rolling case was exercised" from "the
rolling case was not looked at". That is the same shape as the soak that ended `success` while 98% of
its checks failed (`B-117`): a green thing that never asked the question.

## Two ways out, and the second is not a lesser one

- **Run it in `verify`.** That job already has Docker, a stand and the published image; it needs the
  previous tag, which `git describe --abbrev=0 --tags HEAD^` gives, and the minutes the Makefile
  warns about are minutes on a release rather than on every commit — which is exactly the trade the
  Makefile's own sentence proposes.
- **Say it is manual, and make the release ask.** If the minutes are not wanted, the honest form is a
  release checklist step and a line in the workflow that says so — not two comments that mention the
  check while nothing calls it. A guard nobody runs should at least not read as one that runs.

## Acceptance criteria

- AC: either the release workflow runs `rolling-check` against the previous tag, or the workflow says
  in one line that it does not and where the check happens instead.
- AC: whichever is chosen, no comment in that workflow refers to `rolling-check` in a way that
  implies CI performs it.
- AC: if it is automated, it is proved by mutation — a deliberate incompatibility between the two
  versions fails the release.

## Anchors

| What | Where |
|---|---|
| The check | `scripts/rolling-check.sh` |
| The sentence naming where it belongs | `Makefile` (`rolling-check` target) |
| The release that does not run it | `.github/workflows/publish-image.yaml` |
| Why the pair matters | `B-36`, `B-97` |
