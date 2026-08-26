---
id: B-48
title: "Everything that runs this product is a file on somebody's laptop"
status: wip
priority: P2
size: M
stage: stage-m5-upstream
blocked_by: [B-47]
---

# B-48 — Everything that runs this product is a file on somebody's laptop

`deploy/compose.yaml` is the only description of how konekt runs, and it describes a stand: it
publishes the broker's neighbours on host ports, it turns the one-time-code readback ON, it runs the
traffic simulator, and it keeps its database in a docker volume that `make stand-down` deletes.
Every one of those is right for a stand and wrong for anything else, so there is no artefact in this
repository that says what a *deployment* of konekt would be. A reference build whose reference
deployment does not exist is a reference for the half that is easy.

That gap is not only documentary. Three of the decisions this product makes are only *stated* today
and have never been forced to survive a second environment: that the brand is a redeploy and not a
rebuild, that the observability trio is all-or-nothing per agent, and that the development routes are
off unless somebody switches them on. A second environment is the cheapest test any of them will get.

- **The decision and its reason.** A Helm chart in this repository, values in the infrastructure
  repository, deployed by a workflow there — the shape three neighbouring products already use, so
  the operator learns one thing rather than four. The chart carries the SHAPE (what runs, what may
  not be reached, what stops the render), and the cluster's values carry the addresses and the keys.
- The rejected alternative is compose on a host with a reverse proxy in front. It is fewer moving
  parts and it would demonstrate nothing new: the stand already proves the processes fit together,
  and what is unproven is the deployment — an image pulled from a registry, secrets that are not in
  the file, a broker that must not be reachable, and a migration that runs before the server does.
- Also rejected: a chart that takes the database as an address and leaves it to the operator. It
  would be the right shape for a product with a DBA and the wrong one here, where the point is that
  somebody can have the whole thing running from two commands.
- Not covered: horizontal scale (the traffic simulator is per-process — see `server.replicas`),
  backups, a second environment, or any pipeline that deploys on a merge. One deploy by hand first.

- AC: `helm template` refuses, with a sentence naming the value, when the hostname, the image tag,
  the JWT secret or the database password is missing — each of which otherwise produces a green
  deploy and something broken.
- AC: the broker is unreachable from any pod that is not this release's server, and that is asserted
  against the running cluster rather than read off the manifest.
- AC: a subscriber signs in, buys a plan and sees the counter move, against the deployed instance,
  from the desktop client pointed at it with `KONEKT_URL`.
- AC: the deployed instance's records are findable in tracy and metrik under a service name and a
  release that name this build rather than an afternoon.
- Anchors: `charts/konekt/`, `docs/services/konekt-server.md`, `deploy/compose.yaml`.

Background: [B-47](B-47-first-release-tag.md) is what makes an image exist to deploy;
[B-22](B-22-brand-b.md) is the claim the brand value is here to test.
