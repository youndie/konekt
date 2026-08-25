---
id: B-16
title: "The traffic simulator: a consumer that moves the counters"
status: open
priority: P2
size: S
stage: stage-m2-live
blocked_by: [B-14, B-15]
---

# B-16 — The traffic simulator: a consumer that moves the counters

Nothing in this build produces real traffic, and a counter that never moves cannot demonstrate a live
screen. A booblik consumer on `usage` decrements the counters on a schedule, and the decrement travels
back to an open screen through the realtime channel.

- **The decision and its reason.** The simulator publishes to `usage` rather than writing counters
  directly, so the path exercised is the one the real integration would use: broker → consumer →
  counter → realtime → screen. A simulator that wrote the database would prove none of it.
- The rejected alternative is a scheduled job inside the server. Fewer moving parts, and it removes
  the only consumer booblik has.
- Not covered: realistic usage patterns. The rate is configuration, chosen so a counter visibly moves
  during a demonstration.

- AC: with the simulator running, the home screen's data counter decreases without any interaction.
- AC: stopping the simulator stops the movement and nothing else changes.
- Anchors: `server/src/main/kotlin/io/konekt/mocks/traffic/`.

Background: [research-architecture](../research/research-architecture.md) §1.8.
