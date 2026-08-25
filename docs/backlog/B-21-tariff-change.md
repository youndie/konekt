---
id: B-21
title: "Changing tariff, as a saga with a confirmation"
status: open
priority: P2
size: M
stage: stage-m3-product
epic: feature-tariff
blocked_by: [B-08]
---

# B-21 — Changing tariff, as a saga with a confirmation

A tariff change moves money, changes quotas and takes effect on a boundary. It is the second saga, and
its value is that it reuses the first one's machinery without reusing its code — including the
suspend, because a tariff change is exactly the sort of thing a subscriber should confirm.

- **The decision and its reason.** The change takes effect at the next billing boundary rather than
  immediately, and the screen says which date. An immediate change makes proration the centre of the
  feature, and proration is arithmetic this build has nothing to say about.
- The rejected alternative, immediate effect with proration, is more realistic and buys a week of
  edge cases in a mock billing system.
- Not covered: downgrade restrictions. Any tariff can be chosen from any tariff.

- AC: a confirmed change shows the new tariff with its effective date and the old one still current.
- AC: an unconfirmed change past its TTL leaves the current tariff untouched.
- Anchors: `server/src/main/kotlin/io/konekt/tariff/`.

Background: [research-architecture](../research/research-architecture.md) §1.7.
