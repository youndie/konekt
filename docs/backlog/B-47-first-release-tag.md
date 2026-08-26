---
id: B-47
title: "Nothing has ever been released, so three checks stand in for the one that matters"
status: wip
priority: P2
size: S
stage: stage-m5-upstream
---

# B-47 — Nothing has ever been released, so three checks stand in for the one that matters

There is no tag and no published image. Three things work around that today, each honestly and each
less than the real thing:

- `make rolling-check` takes a **commit** where it wants a tag (`B-35`). It is a fair stand-in only
  while the schema has moved since that commit, and it refuses when it has not — but "the previous
  release" chosen by hand is not the previous release.
- The stand builds its server image locally on every `stand-up`, so nothing ever runs an image somebody
  else built.
- The observability records carry `RELEASE=stand` and `ios-b27`, which are names of runs rather than of
  releases. A crash group that cannot say which build produced it is one nobody can act on, and these
  can only say which afternoon.

- **The decision and its reason.** Tag `v0.1.0`, publish the server image, and point `rolling-check` at
  the tag by default — which it already does when one exists. The version is what turns three
  approximations into one fact, and it costs a tag rather than a feature.
- The rejected alternative is waiting for the product to be "finished". It is a reference build; there
  is no finish line, and the checks that need a previous release need it now rather than later.
- Not covered: a changelog, semantic-version policy, or a release workflow in CI. One tag by hand
  first; automating a thing that has happened once is how the automation ends up wrong.

- AC: `make rolling-check` with no argument runs against the tag and passes.
- AC: a crash report and a tracy record from a build of that tag name the tag rather than a run.
- AC: `deploy/compose.yaml` can be pointed at the published image instead of building one, and the e2e
  suite passes against it — which is the first time anything here runs an artefact rather than a tree.
- Anchors: `scripts/rolling-check.sh`, `deploy/compose.yaml`, `.github/workflows/`.

Background: [B-35](B-35-e2e-compose-stand.md) for the check that is waiting for it.

<!-- Tagging and publishing are outward-facing and irreversible; this item is written down rather than
     done, and the decision belongs to whoever owns the repository. -->

## What landed

**`v0.1.0` is tagged and pushed**, on a commit CI had already gone green on — tagging a red one is a
release nobody can trust and a check nobody can run against.

**The stand can run an ARTEFACT rather than a working copy**, which nothing here had ever done.
`SERVER_IMAGE` names an image and `up --no-build` uses it; unset, the default is a local tag this tree
produces, so the ordinary path is unchanged. Proved by running the whole `:e2e` suite against
`ghcr.io/youndie/konekt-server:v0.1.0` with `docker inspect` confirming which image was serving.

**A crash report names the release.** With `RELEASE=v0.1.0` the stand's katcher holds
`('v0.1.0', 'stand')` where it used to hold the name of an afternoon.

**`make rolling-check` takes the tag.** With no argument it reads `git describe` and finds `v0.1.0` —
and then REFUSES, because the tag is HEAD and no migration has moved since it. That is the check
working: a green run of the same code against the same schema is a claim about rolling deploys backed
by nothing. It passes meaningfully the moment there is a second release, and it still takes a commit
in the meantime.

## Why this is `wip` and not `done`

**The image is built and not published.** Pushing to the registry needs a token with
`write:packages`, and the one this repository's tooling has carries `read:packages`. That is one
command with a credential rather than work, and inventing a way around it would be worse than saying
so:

```bash
make release-image VERSION=v0.1.0
docker push ghcr.io/youndie/konekt-server:v0.1.0
```

Everything the third acceptance criterion is FOR is proved without it — the stand runs an image, the
suite passes against it, and the image is named after a release. What is unproven is the registry
round trip.

## One thing the build machine cannot do

`make release-image` reads the version from `git describe`, and the machine that has Docker has no git
checkout — the tree gets there by file sync. So on that box the version is passed explicitly, and the
target says so rather than building an image called `konekt-server:`.
