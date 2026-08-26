---
id: operator-boundaries
title: What an operator can change, and what it costs
type: service
status: active
repo_url: https://github.com/youndie/konekt
# EVERY MODULE, and that is what this document is: the axes cut across all three, and which cost an
# axis carries is decided by which of them has to change.
module: server, client, broker
tech_stack: [Kotlin/JVM 25, Compose Multiplatform, booblik, kompot]
owner: unassigned
tags: [white-label, operations, boundaries]
---

# What an operator can change, and what it costs

The product's claim is that an operator rebrands the box without development. That is true, with
boundaries — and **a boxed product whose boundaries are discovered by the buyer is a support contract
rather than a box.** So they are written down here, per axis, with the research section that
establishes each one.

Written as a table because a paragraph lets the awkward rows hide.

## The four costs

| Cost | What it means |
|---|---|
| **configuration** | an environment variable and a restart of one process |
| **server deploy** | a new server image; clients are untouched |
| **client release** | a new build in the stores, on the subscriber's update schedule |
| **broker restart** | booblik's topics are fixed at startup, so the broker itself must be restarted |

The order matters: each row down is slower than the one above it, and the last one a subscriber
controls rather than the operator.

## The table

| Axis | Cost | Why | Established in |
|---|---|---|---|
| Colours and typography | server deploy | The kit is served over HTTP and the client applies it without a rebuild — but the kits themselves are resources inside the server image (`server/src/main/resources/themes/`), so a NEW palette is a deploy. | [§1.2](../research/research-architecture.md), [§1.3](../research/research-architecture.md) |
| Which of the shipped brands is served | **configuration** | `BRAND` picks among the kits the image already carries. This is the only row that is a variable and a restart. | `KonektConfig.brand` |
| Copy, screens, layouts, flows | server deploy | Every string and every tree is composed on the server; the client renders what it is given and formats nothing (D15). | [§1.2](../research/research-architecture.md) |
| A new value in an open vocabulary — a counter state, an order status, a plan state | server deploy | These are open strings on the wire on purpose. A client one release behind draws the ordinary card rather than nothing. | [§1.5](../research/research-architecture.md) |
| The shape scale (corner radii) | **client release** | The wire has no vocabulary for shape and kompot protects that deliberately. The client resolves a brand NAME to a scale it was compiled with. | [§1.2](../research/research-architecture.md) |
| A brand the client has never heard of | **client release** to get its shapes | It is served and rendered immediately — with brand A's radii, silently. `BrandKitsTest` fails when the server ships a kit no scale answers for, so the gap is caught in CI rather than by a subscriber. | [§1.2](../research/research-architecture.md) |
| A new kind of component | **client release** | The dictionary is the API. An unknown type draws the degradation block — never a blank gap — and is reported with its wire name, so an operator can see which build is behind and how often. | [§1.5](../research/research-architecture.md), [§1.4](../research/research-architecture.md) |
| A new event topic | **broker restart** | booblik fixes its topics at startup and has no replication. | [§1.8](../research/research-architecture.md) |
| The plan catalogue and its prices | server deploy | `StaticPlanCatalog` is in the server's code. A real MVNO reads a BSS; this build does not, and says so. | `feature/purchase-server-data` |
| The tariff behind a custom package | server deploy | One function, no campaign layer. The client is never given a price table — a price computed on the client is a price a client can argue with. | [feature-plan-purchase](../features/feature-plan-purchase.md) |
| A database schema change | server deploy, **twice** | Expand and contract are separate releases. A differ emits the shortest SQL that makes two schemas equal, which is `DROP COLUMN` and `RENAME` — exactly what breaks a rolling deploy. | `B-36` |
| Where the observability agents report | **configuration** | Endpoint and key per agent. Both absent is a decision; one absent is refused at startup, because a deployment that meant to be observed and is silent looks exactly like one that is working. | [§1.9](../research/research-architecture.md) |
| Crash reporting on iOS | delivered | katcher publishes every Apple target since `client:0.6.2`, and a simulator crash arrives naming its release. | [§1.9](../research/research-architecture.md), `B-27` |
| Structured logging on iOS | delivered | tracy publishes the three iOS targets since `0.1.13`. Before that it was unavailable on the platform where an out-of-date build is likeliest. | [§1.9](../research/research-architecture.md), `B-26` |

## The rows worth arguing with

**"Colours are a server response" is true and is not configuration.** A client applies a served kit
without a rebuild, which is the claim that matters for the update schedule — and the kit is a file in
the server image, so producing a new one is a deploy. Both halves are true and only one of them is
usually said.

**A brand without a shape scale is served successfully and looks wrong.** The client falls back to
brand A's radii silently, because refusing to draw a screen over a corner radius is worse than drawing
it with the wrong one. What makes that safe is not the fallback: it is the test that fails when the
server ships a kit no scale answers for.

**The slowest row is the one an operator does not control.** A client release lands on the
subscriber's schedule, not the operator's — which is why the two rows that need one are the two the
architecture works hardest to avoid needing.

## Not covered

Pricing, packaging and anything commercial. Also anything about a real BSS, OCS, SM-DP+ or payment
provider: every external system in this build is a mock, and what it would cost to change a real one
is a fact about that vendor rather than about this box.
