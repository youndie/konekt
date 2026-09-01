---
id: B-106
title: "A deploy with --reuse-values runs a shape the chart guard has never rendered"
status: done
priority: P1
size: S
stage: stage-m7-completeness
---

# B-106 — The chart said one thing and the cluster ran another, and every check was green

`v0.1.26` was deployed with

```bash
helm upgrade konekt charts/konekt -n konekt --reuse-values --set server.version=v0.1.26
```

and the broker came up with **retention off**:

```
BOOBLIK_SEGMENT_CAPACITY_BYTES=
BOOBLIK_RETENTION_BYTES=
BOOBLIK_RETENTION_MILLIS=
```

Three values `B-100` added to `values.yaml`, quoted by `B-91`'s follow-up so they would not render as
floats, present in the chart, absent from the container.

## Why

`--reuse-values` is documented as *reuse the last release's values and merge in any overrides*, and
what it reuses is the previous release's **user-supplied config** — which is then used **instead of**
coalescing with the new chart's `values.yaml`. A key the chart gained since the last deploy has no
value in the old config, so it renders empty. The previous release was chart `0.2.0`, before those
three keys existed.

So the flag does not mean *keep what I set*. It means *pretend the chart still has the values it had
last time* — and every value added between two deploys is silently dropped.

## Why no check saw it

`scripts/chart-check.sh` renders **the chart**: `helm template konekt charts/konekt --set …`. That
render is correct and always has been. What ran in the cluster was a different render, produced by a
flag the chart cannot know about. The guard and the deployment were never looking at the same thing.

This is the same shape as [B-97](B-97-the-rolling-check-can-run-a-stale-binary.md) — a check that
passes over an artefact nobody deployed — and the same shape as the failure `scientific()` was written
for: `ComposeStandTest` read the FILE while the RENDER was what broke.

## What was done immediately

Redeployed with `--reset-then-reuse-values` (Helm 3.14+), which resets to the new chart's defaults and
then re-applies the previous release's user-supplied values. The broker then said:

```
retention: bytes=134217728 millis=21600000 check=30000
```

That is a fix for one deploy, not for the next one.

## Acceptance criteria

- AC: the repository states, in one place a person deploying will actually read, which flag a konekt
  upgrade uses and why `--reuse-values` is not it.
- AC: a check compares what the CLUSTER runs against what the CHART renders — at minimum, that no
  environment variable the chart declares is empty in the running deployment. An empty value is the
  observable form of this defect and needs no cluster-specific knowledge to assert.
- AC: the check fails when the flag is wrong. Proved by mutation: deploy the previous chart's values
  against the current chart and watch it go red.
- AC: whatever is written down says what `--reset-then-reuse-values` does and what it does NOT do —
  it re-applies user-supplied values, so a value the operator set and the chart later removed is
  still carried forward.

## What was done

**The flag is written down as a target rather than as a sentence.** `make deploy` is
`helm upgrade --reset-then-reuse-values`, and it runs the check itself — a guard that has to be
invoked separately runs on the deploys nobody was worried about.

**`scripts/deploy-check.sh` + `scripts/deploy_check.py`** compare the environment the CLUSTER is
running against the environment this chart renders **with the release's own user-supplied values** —
which is exactly what the right flag produces and exactly what the wrong one does not. Three
assertions, in this order:

1. no value in the cluster is **empty** — the observable form of this defect, and true independently
   of whether the working tree's chart still matches what was deployed;
2. every variable the chart declares is present in the cluster with the same value — which catches
   the case the first misses, a variable wrapped in `{{- if }}` that renders to nothing at all;
3. neither side is empty, because every statement above is satisfied by two empty dictionaries.

The comparison lives in a separate `.py` **so it can be run on files**. Proving assertion 1 fires
would otherwise have meant deploying a broken release to prove a check works.

## Proved by mutation

| Mutation | Result |
|---|---|
| control, unmutated | `ok 34 environment values` |
| a chart default changed to `999` | `different: … chart '999', cluster '134217728'` |
| a new `BOOBLIK_SOMETHING_NEW` added to the template | `the chart declares it and the cluster has not got it` |
| **this defect itself** — the three broker values blanked in the cluster's manifest | `empty in the cluster: … BOOBLIK_RETENTION_BYTES=` ×3, plus three `different` |
| both inputs empty | `every comparison above was vacuous` ×2 |

## One thing found while writing it

The first draft of `make deploy` refused an empty `VERSION` with an explanatory message. The branch
was **unreachable**: `VERSION ?= $(shell git describe --tags --abbrev=0)` is already defined above it
for `release-image`, so the variable was never empty and the refusal never ran — a guard that cannot
fire, which is the mirror of the guard that cannot be skipped. Removed, and the comment now says
what actually refuses an empty version: the chart, at render time, with
`server.version is required — a tag that moves leaves helm nothing to notice`, which
`chart-check.sh` already proves names its own reason.

## Anchors

| What | Where |
|---|---|
| The render that is checked | `scripts/chart-check.sh` |
| The values that were dropped | `charts/konekt/values.yaml` (`broker.segmentBytes`, `retentionBytes`, `retentionMillis`) |
| Where they reach a container | `charts/konekt/templates/broker.yaml` |
| The sibling defect | [B-97](B-97-the-rolling-check-can-run-a-stale-binary.md) |
| The check written for it | `scripts/deploy-check.sh`, `scripts/deploy_check.py` |
| The flag, as a target | `Makefile` — `deploy`, `deploy-check` |
| Where a deployer reads it | [konekt-server](../services/konekt-server.md) §5 |
