---
id: B-16
title: "The traffic simulator: a consumer that moves the counters"
status: wip
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

- AC OK: the whole chain, every link real — the simulator publishes to a **real broker**, a consumer
  reads the topic, the counter goes from 10 000 to 9 975, and the new card is pushed to that
  subscriber's stream. A test that wrote counters directly would prove the arithmetic and nothing
  about the path, and the path is what this item exists for.
- AC OK: with nothing published, the counter is asserted to be **exactly** where it was rather than
  merely not higher — a consumer that re-applied its last event would pass the looser check.
- Also: a counter is floored at zero in SQL. A screen that says minus three hundred and ninety
  megabytes is worse than one that says zero, and the clamp is two mutually exclusive `UPDATE`s
  rather than a `CHECK`, because a check refuses the write and leaves the caller to handle a failure
  that has an obvious right answer.
- Also: the copy changes with the state and not only the colour, which is the canvas's rule and the
  reason `state` is on the wire at all.
- Also: usage for a subscriber who has bought nothing is ignored rather than failing. The simulator
  does not know who owns what, and a consumer that threw there would stop the poll for everybody else.
- AC PENDING, found in `B-07`: **nothing starts the simulator or its consumer.** Both classes exist,
  both are covered end to end against a real broker, and the running server constructs neither — the
  chain is reachable only from `TrafficChainTest`. That is not a gap in this item's ACs, which is the
  point worth keeping: every one of them was about the chain being *tested*, and a chain that is
  tested and never started passes all of them. `FeatureModulesReachTheGraphTest` now catches the DI
  half of this class of defect; a worker nobody starts is the half it does not catch yet.
- Also carried: the usage feature's own wiring landed in `B-07` rather than here — the counters were
  in the graph of nothing, and a completed purchase granted no allowance.

- Anchors: `server/src/main/kotlin/io/konekt/mocks/traffic/`,
  `feature/usage-server-domain/`, `feature/usage-server-data/`,
  `shared/db/src/main/resources/db/migration/V7__usage_counter.sql`,
  `server/src/test/kotlin/io/konekt/mocks/TrafficChainTest.kt`.

## Where counters come from

A purchase grants; nothing else does. That is why this item created the counter feature rather than
seeding rows from nowhere — a counter that exists without having been bought is a number with no
story behind it, and the first screen to show one would have to invent an explanation.

The consumer's position lives in the consumer, because booblik stores no offsets — that absence is
what removes the group coordinator and the cluster consensus behind it. This one resumes from where
the broker is **now** rather than from zero, which is right for simulated traffic and wrong for
anything real: replaying a day of usage on a restart would empty every counter in the product. Said
here because the correct choice for a real integration is the opposite one.

Background: [research-architecture](../research/research-architecture.md) §1.8.
