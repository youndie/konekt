---
id: B-47
title: "Nothing has ever been released, so three checks stand in for the one that matters"
status: open
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
