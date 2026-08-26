---
id: B-48
title: "Everything that runs this product is a file on somebody's laptop"
status: done
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

## What landed

**It is deployed and serving**, from a published image rather than from a tree, with Postgres and the
broker beside it. The chart is in this repository; the deployment's own values and the workflow that
applies them live with the cluster, which is the split this item chose.

**The whole product works against it, driven over its public address.** A number asked for a code, the
code was read from the server's log — the mock delivery writes it at WARN, and that is the sign-in
path when `dev.revealOtp` is off — the session came back as an `update_session`, a top-up landed, a
plan was bought and confirmed, and the balance had fallen by the price. Then the data counter moved on
its own, `progress` 0.00488 → 0.00732 in twelve seconds, which is the broker chain running in a
cluster rather than in compose: simulator, broker, consumer, counter, screen.

**The broker is closed, and the rule is proved in both directions.** A throwaway pod in the same
namespace cannot open 9092 on it; the same pod wearing the server's label can. Either half alone
would have been worthless — a refusal on its own is equally consistent with a broker that is simply
not listening, which is the shape of negative result this repository has been caught by before.

**The precondition check was wrong in a way worth keeping.** It asked whether the RoleBinding granting
the deploy account its rights EXISTED, and failed on a namespace where that binding had been applied
two minutes earlier: the account's role covers exactly what the chart renders, and reading role
bindings is not among those rights. So the read answered Forbidden, the check read Forbidden as
absent, and it advised applying what was already applied. `kubectl auth can-i` is what it should have
been — a SelfSubjectAccessReview any authenticated client may create, answering about the operation
actually needed rather than about an object nobody may read.

## The fourth criterion, and the three different ways it was reached

**tracy: met.** The deployment reports one instance, and the purchase made through the public address
is findable by its `orderId`. Read at the collector rather than at the agent, which is the only place
that distinction can be made at all.

**metrik: met, and it took a second pair of eyes.** Its ingest is UDP — a send always succeeds, so
nothing on the sending side can tell a working pipeline from a silent one — and the collector was not
readable from where the check was being run. So it was recorded as UNCONFIRMED rather than as absent
or as working, and confirmed afterwards by someone who could read it. The distinction is the point:
"I could not look" is a third answer, and collapsing it into either of the other two is how a
deployment comes to believe it is observed.

**katcher: met last, and it is the one that could not be wired in advance.** `Katcher.catch` posts
with an APPLICATION key — not an installation-wide one like the other two — and the collector looks it
up; a key naming no application is every report refused, silently, and from the agent's side that is
indistinguishable from an agent that never started. So the deployment ran with both fields empty,
which the server reads as a decision rather than as a half-configuration, until the application was
registered and its GENERATED key existed to point at. That is the same reason the compose stand seeds
its collector directly instead of calling an endpoint.
