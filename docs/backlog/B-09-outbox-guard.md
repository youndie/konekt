---
id: B-09
title: "Refuse to boot on a repository that silently drops events"
status: open
priority: P0
size: S
stage: stage-m1-money
blocked_by: [B-02]
---

# B-09 — Refuse to boot on a repository that silently drops events

petich's readme is explicit: handed a repository without outbox support, *"the engine quietly falls
back to a plain update and drops the events"*. The saga still completes, its state is still correct,
and every assertion anyone naturally writes still passes. The consumer on the other end simply never
runs. This is the single failure mode in the build that is invisible from every direction.

**The first half is now a configuration flag.** petich#3 closed on 2026-08-25 and `0.1.0.6` carries
`PetichEngineConfig(requireOutbox = true)`, which refuses to build an engine whose repository cannot
store events, plus `PetichEngineMetrics.onDroppedEvents` for the case where a deployment wants the
fallback and wants to see it. Both are off by default, so nothing changes for an application that
wants no events.

- **The decision and its reason.** Set `requireOutbox = true` instead of hand-writing the startup
  assertion — a guard in the library is one the next project inherits, and one written here is one
  the next project writes again from scratch, or more likely does not.
- **The second guard stays and is not redundant**: a test that reads the **outbox row** inside the
  committed transaction of a saga. `requireOutbox` proves the repository *can* store an event; the
  test proves that a real saga actually *did*. Those are different claims, and the interesting
  failures live in the second.
- The rejected alternative is an end-to-end test that waits for a message on the topic. It is slower,
  it is flaky, and it fails for a dozen reasons that are not this one — so when it goes red nobody
  looks here.
- Also wired: `onDroppedEvents` into metrik, so a deployment that ever does take the fallback sees a
  non-zero line rather than nothing.

- AC: wiring an in-memory repository without outbox support fails at engine construction, with
  `requireOutbox = true` set in the configuration and asserted by a test.
- AC: a committed four-step saga leaves exactly one outbox row, asserted against the table.
- Anchors: `server/src/main/kotlin/io/konekt/saga/EngineWiring.kt`,
  `server/src/test/kotlin/io/konekt/saga/OutboxWiringTest.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.7, D6, Risk 1.
