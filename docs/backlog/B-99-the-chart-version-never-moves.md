---
id: B-99
title: "The chart states a versioning rule in its own comment and has never followed it, so nothing downstream can pin a render"
status: open
priority: P2
size: S
stage: stage-m7-completeness
---

# B-99 — An image tag pins the binary and nothing pins the shape

`charts/konekt/Chart.yaml` carries `version: 0.1.0`, written when the chart was created
(`bb3e724`) and never moved since. The file also explains, in a comment above `appVersion`, exactly
why that number matters:

> A chart version and an application version are two different lifetimes here: the shape of this
> deployment changes when a template does, and the binary changes when a tag is cut in this
> repository.

The rule is right and it is not kept. Since the chart was created its templates have changed once in
a way that is precisely "the shape moved" — [B-91](B-91-a-second-replica-loses-live-updates.md) added
a refusal of any `replicas > 1` to `templates/server.yaml` and three values beside it — and the chart
version stayed at `0.1.0`.

## What that costs

**A release tag pins the binary and says nothing about the render.** `ghcr.io/youndie/konekt-server:<tag>`
is immutable by policy, and a deployment that names one gets exactly the server that tag was built
from. The templates that turn it into pods are taken from this repository's default branch, because
there is no released chart version to ask for — the number that would name one has never moved.

So the two halves of a deployment have different pinning stories, and only one of them is written
down:

- roll the image back to an older tag and it renders under **today's** templates. `B-91`'s replica
  guard is the concrete case: an image built before that item would now be refused a second replica
  by a chart the image knows nothing about. Benign in that direction, and the general case is not —
  a template that starts requiring a value an older image does not read fails at render, and one that
  stops passing a value an older image needs fails at start;
- `helm upgrade --atomic` rolls back to the previous **release**, which is a previous render of
  whatever the chart was at that moment. What you land on is therefore not pinned either.

**And `scripts/chart-check.sh` cannot see this.** It proves the guards in the templates say what they
mean — each refusal naming its own reason — which is a different question from whether the templates
that were proved are the templates that will be rendered.

## The decision

- **The chart version moves when the chart's shape moves, and a check enforces it.** Anything changed
  under `charts/konekt/templates/` or in `charts/konekt/values.yaml` without `version:` in
  `Chart.yaml` changing, relative to the base branch, is a failure. This is the same shape as the
  backlog-numbering check that already compares against the base branch, and it belongs beside it.
- **Templates and values, not the whole file.** A `description` edit is metadata and moving the
  version for prose would train people to bump it without thinking, which is how a version number
  stops meaning anything. Helm's own semantics are stricter; this build's reason for the number is
  the comment quoted above, and that comment is about shape.
- **The rejected alternative is publishing the chart as an artefact per release**, so a deployment can
  ask for a version rather than a branch. That is the complete fix and it is a packaging pipeline,
  a registry decision and a retention policy — a lot to add to a reference in order to demonstrate
  nothing about the six toolkits. The version moving is what makes it *possible* later, which is why
  it comes first.
- **Out of scope: how any particular deployment consumes this chart.** That lives outside this
  repository and is not this item's to change. What is in scope is that this repository currently
  offers nothing to pin.

## Acceptance criteria

- AC: `Chart.yaml`'s `version` is brought to a number that reflects the shape changes already made,
  with the reasoning recorded rather than a bump chosen by feel.
- AC: a check fails when a template or `values.yaml` changes without the chart version changing,
  compared against the base branch, and it runs in CI.
- AC: proved by mutation, both directions — a template change without a bump is refused, and the same
  change with a bump passes. A guard that only fires one way has never been shown to pass for the
  right reason.
- AC: the check says which files moved and what the version is, because "bump the chart" is not an
  instruction anybody can act on without reading the diff.
- AC: `docs/services/konekt-server.md` §5 records the rule beside the chart's description, so the next
  template change is written by somebody who knows it.
- AC: `make check` green.

## Anchors

| What | Where |
|---|---|
| The version that has not moved | `charts/konekt/Chart.yaml` |
| The comment that states the rule | `charts/konekt/Chart.yaml`, above `appVersion` |
| The shape change that did not bump it | `charts/konekt/templates/server.yaml`, `charts/konekt/values.yaml` (`B-91`) |
| What already checks the chart, and does not check this | `scripts/chart-check.sh` |
| The base-branch comparison to copy | `.github/workflows/check.yaml` |
