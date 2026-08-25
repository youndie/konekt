---
id: B-10
title: "A payment mock that can refuse and can be slow"
status: open
priority: P1
size: S
stage: stage-m1-money
blocked_by: [B-08]
---

# B-10 — A payment mock that can refuse and can be slow

The canvas draws four purchase states, and one of them is the rollback. A mock that always succeeds
can draw three of them. The demonstration's payload is compensation, and compensation cannot be shown
without a refusal on demand.

- **The decision and its reason.** In-process, behind the interface a real gateway integration would
  implement, with a configuration switch for always-succeed / refuse / delay. The interface boundary
  is what keeps the swap honest; a separate mock process would add operational surface and show
  nothing extra.
- The rejected alternative is a random failure rate. A demo that fails one time in ten is a demo that
  fails during the demo and works during the rehearsal.
- Not covered: any real card handling. Nothing resembling a card number enters this build.

- AC: with the switch at `refuse`, a purchase reaches the rollback screen with the balance restored.
- AC: with the switch at `delay`, the processing state is visible long enough to photograph.
- Anchors: `server/src/main/kotlin/io/konekt/mocks/payment/`.

Background: [research-architecture](../research/research-architecture.md) D10.
