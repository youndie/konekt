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

## The half that was taken, and why the other could not be

**Done: the workflow says what it does not do.** Both mentions of `rolling-check` there were prose —
"an image called after a branch is one no `rolling-check` can ever point at", "`rolling-check`
compares a release against itself without knowing" — and that is how it read as machinery. They now
say `make rolling-check`, and a paragraph at the top states outright that nothing in the file runs
it, that it is typed by a person, and that **a green release means the published image was stood up
and walked alone**. A reader of the workflow can no longer conclude otherwise.

**Not done: running it, because it cannot be proved today.** AC3 asks for a mutation — a deliberate
incompatibility between two versions failing the release — and a release is a pushed git tag: there
is no local target a release passes through (`make release-image` only builds the same image
outside CI, and says publishing "is a tag, not a push"). Proving the automation therefore means
cutting a tag, which is the owner's decision and not a thing to do inside a re-verification pass.
The build box that could rehearse it is held by another session.

**Three shapes, and the third was not in the original list:**

- the `verify` job, which already has Docker, a stand and the published image, plus the previous tag
  from `git describe --abbrev=0 --tags HEAD^`;
- a weekly run against the newest tag, beside the anchors job that already has a `schedule` in
  `check.yaml` — it costs minutes once a week, needs no release to happen, and can be rehearsed with
  `workflow_dispatch` before anyone relies on it;
- keeping it manual and saying so, which is the half now done and which the item still does not
  treat as lesser.

The second is the cheapest to prove and the only one that runs without waiting for a release. This
item stays **open on that decision alone**.

## Acceptance criteria

- AC **met**: either the release workflow runs `rolling-check` against the previous tag, or the
  workflow says in one line that it does not and where the check happens instead.
- AC **met**: whichever is chosen, no comment in that workflow refers to `rolling-check` in a way
  that implies CI performs it.
- AC: if it is automated, it is proved by mutation — a deliberate incompatibility between the two
  versions fails the release.

## Anchors

| What | Where |
|---|---|
| The check | `scripts/rolling-check.sh` |
| The sentence naming where it belongs | `Makefile` (`rolling-check` target) |
| The release that does not run it | `.github/workflows/publish-image.yaml` |
| Why the pair matters | `B-36`, `B-97` |
