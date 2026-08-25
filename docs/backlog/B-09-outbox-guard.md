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

- **The decision and its reason.** Two guards, both cheap, both placed before the damage. A startup
  assertion that the configured repository implements the outbox interface and refuses to construct
  the engine otherwise — construction time, not fallback time, because by fallback time the process
  is serving traffic. And a test that reads the **outbox row** inside the committed transaction of a
  saga.
- The rejected alternative is an end-to-end test that waits for a message on the topic. It is slower,
  it is flaky, and it fails for a dozen reasons that are not this one — so when it goes red nobody
  looks here.
- Not covered: the upstream counter, raised as [U4](../research/research-upstream-proposals.md#u4).

- AC: wiring an in-memory repository without outbox support fails at startup with a message naming
  the repository class and the consequence.
- AC: a committed four-step saga leaves exactly one outbox row, asserted against the table.
- Anchors: `server/src/main/kotlin/io/konekt/saga/EngineWiring.kt`,
  `server/src/test/kotlin/io/konekt/saga/OutboxWiringTest.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.7, D6, Risk 1.
