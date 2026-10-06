---
id: B-135
title: "The traffic simulator and the outbox relay run once per replica"
status: wip
priority: P1
size: M
stage: stage-m8-two-replicas
blocked_by: [B-134]
---

# B-135 — the singletons run on the leader, elected by vojak on konekt's own Postgres

Every pod starts every worker (`Application.kt`, the `ApplicationStarted` block). Three of them are
wrong twice: the **traffic simulator** publishes fictional usage, so two pods spend a subscriber's
allowance twice as fast; the petich **outbox relay** has no claim — no `FOR UPDATE`, no `SKIP LOCKED`
— so two pods publish each outbox row; and the **usage consumer**, correct after B-134 on any number
of replicas, does every fetch twice and rolls half its transactions back. The petich sweeper is safe
as it is: its claim is an optimistic `PENDING_SIGNATURE -> COMPENSATING` update.

- **The decision: vojak's election over `vojak-jdbc`** (youndie/vojak B-17), on the `DataSource` konekt
  already has — no second driver, no second pool. The three run inside `whileLeader`; the election
  joins kore's stop through `vojak-kore`, so a drained pod lets go before it exits and the successor
  skips the wait of vojak's D12.
- The pool grows by one: a held leadership keeps one connection for as long as it lasts.
- The leader's epoch is written next to the simulator's and the relay's effects only if a stale-write
  scenario shows a need — the consumer is already fenced by B-134's compare-and-set.
- Rejected: a claim column in petich's outbox (an upstream change for a problem the leader removes),
  `replicas: 1` for a worker deployment split from the API (two charts for one demonstration).
- Not covered: the realtime bus (B-136).

- AC: with two server instances against one database, the simulator publishes at one instance's rate,
  each outbox row reaches booblik once, and exactly one instance reports itself leader.
- AC: stopping the leader hands every singleton to the other within one poll; killing it, within
  `localLease`.
- Anchors: `server/src/main/kotlin/io/konekt/Application.kt`,
  `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficSimulator.kt`, `server/build.gradle.kts`.
