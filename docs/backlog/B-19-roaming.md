---
id: B-19
title: "Roaming: status, zones and packages bought before the trip"
status: open
priority: P2
size: M
stage: stage-m3-product
epic: feature-roaming
blocked_by: [B-08]
---

# B-19 — Roaming: status, zones and packages bought before the trip

A roaming package is bought at home and starts counting abroad. The canvas's plan detail says it
plainly — *"the plan starts counting on first connection, not now"* — and that sentence is the whole
feature: the product sells something that does nothing until it is used.

- **The decision and its reason.** A package carries a zone (`home` or a roaming zone) and an
  activation trigger, and the counter for a roaming package is created dormant. The purchase saga is
  the same saga; only the provisioning step differs, which is what keeps this from becoming a second
  purchase flow.
- The rejected alternative is a separate roaming order type. It duplicates the compensation logic,
  which is the part nobody wants two copies of.
- Not covered: real network attachment. Nothing observes a device landing anywhere; first use is
  simulated by the traffic simulator against the roaming counter.

- AC: a roaming package bought at home shows as bought and not yet counting.
- AC: the same package after simulated first use counts down and its expiry is dated from that moment.
- Anchors: `server/src/main/kotlin/io/konekt/roaming/`.

Background: [design-app-canvas](../design/design-app-canvas.md) section 02.
