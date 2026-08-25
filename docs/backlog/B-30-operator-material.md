---
id: B-30
title: "Operator material: what is configuration and what is a release"
status: open
priority: P2
size: S
stage: stage-m5-upstream
blocked_by: [B-22, B-27]
---

# B-30 — Operator material: what is configuration and what is a release

The product's claim is that an operator rebrands the box without development. Research found three
places where that is true with a boundary, and a boxed product whose boundaries are discovered by the
buyer is a support contract rather than a box.

- **The decision and its reason.** One document listing, per axis, whether a change is configuration,
  a server deploy or a client release: colours and typography (server), copy and screens (server),
  shape scale (client release, §1.2), a new component (client release, §1.5), a new broker topic
  (broker restart, §1.8), crash coverage on iOS (absent, §1.9). Written as a table, because a
  paragraph lets the awkward rows hide.
- The rejected alternative is putting the caveats in the README's small print, which is where they go
  to not be read.
- Not covered: pricing, packaging or anything commercial.

- AC: the table exists, and every row cites the research section that establishes it.
- AC: no claim in the README contradicts a row in it.
- Anchors: `docs/services/`, `README.md`.

Background: [research-architecture](../research/research-architecture.md) §1.2, §1.5, §1.8, §1.9.
