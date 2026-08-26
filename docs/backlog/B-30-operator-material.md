---
id: B-30
title: "Operator material: what is configuration and what is a release"
status: done
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

## What landed

[operator-boundaries](../services/operator-boundaries.md): fourteen rows, each naming one of four
costs — a variable and a restart, a server deploy, a client release, a broker restart — and each
citing the research section that establishes it.

- AC MET: the table exists and every row cites its source.
- AC MET: no claim in the README contradicts a row. The README's own white-label table was the thing
  most at risk, and it needed correcting in one place: it said colours "ship as a server response — no
  rebuild", which is true about the CLIENT's update schedule and hides that the kits are resources
  inside the server image. Both halves are true and only one of them is usually said.

## The row that had gone stale, and the amendment it forced

Research §1.9 said iOS is not covered at all. That was true when it was written and both causes were
upstream — katcher published no Apple target, and neither did tracy. Both are closed now, and a
document citing §1.9 for "crash coverage on iOS: absent" would have been citing a fact that had
expired. The section is amended at the point of divergence rather than rewritten: the reasoning in it
is what made the two gaps legible enough to file, and filing them is what closed them.

**The ordering is the part worth keeping.** Each cost is slower than the one above it, and the slowest
— a client release — is the one an operator does not control: it lands on the subscriber's update
schedule. That is why the two axes needing one are exactly the two this architecture works hardest to
avoid needing.
