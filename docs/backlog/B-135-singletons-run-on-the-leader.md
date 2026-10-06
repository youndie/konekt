---
id: B-135
title: "The traffic simulator and the outbox relay run once per replica"
status: done
priority: P1
size: M
stage: stage-m8-two-replicas
blocked_by: [B-134, B-136]
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

## Findings — 2026-10-06

- **Done.** `io.konekt.leader.Singletons` campaigns through `vojak-jdbc` 0.1.0.6 (reposilite) on the
  server's own `DataSource`; the outbox relay, the usage consumer and the simulator are started inside
  its block in `Application.kt`, in a supervisor scope so one failing worker does not take the others
  or the leadership with it. The sweeper and the update broadcaster stay on every replica. The pool
  default is 11. The `workers` shutdown participant closes the election before cancelling the scope.
- **`vojak-kore` was not used, deliberately.** It pins kore 0.1.15 through `api` and kore pins Ktor the
  same way — konekt is on kore 0.1.4 and Ktor 3.5.2, so it would have moved Ktor under the server: a
  green build and an `IrLinkageError` at run time. The participant konekt already has does the same job.
- **The outbox duplicate was real, not theoretical.** With the election removed from `Singletons`, two
  relays published 20 rows as 40 records — every one twice. With it, 20.
- **AC, walked:** exactly one replica runs the singletons and a clean stop hands them over inside
  `localLease` (`of two replicas exactly one runs…`); the leader's session ended by the server, sampled
  every 5 ms for `localLease` + 3 s, never two copies (`when the leader's session is ended…`); each
  outbox row reaches the broker once with two replicas (`with two replicas each outbox row…`). The
  mutant above turns all three red.
- **Not exercised here:** two whole server processes. The tests run two elections against one database
  with the real relay and the real store; the chart and a pod kill on the stand are
  [B-138](B-138-two-replicas-on-the-stand.md).

## Iteration 2 — 2026-10-06: the stand, and why this waits for B-136

- **One election for all three was wrong, and CI's e2e said so.** The stand runs two servers on one
  database — `server` with the simulator on and `server-declining` with it off. `server-declining` won
  the election in CI and nothing published usage. The simulator is a singleton among the replicas that
  have it switched on, so it now has an election of its own (`Singletons.SIMULATOR`) that only such a
  replica joins; the relay and the consumer keep `singletons`. Test: `a replica with the simulator off
  leading the singletons does not stop the one with it on`.
- **Then `LiveUpdateScenarioTest` failed for the reason B-136 exists.** With the consumer on one replica
  — `server-declining` on the stand — the decrement is pushed through that replica's in-memory
  broadcaster, and the scenario's SSE client is attached to `server`. Before this item every server
  consumed every event, so each pushed to its own clients: the stand's double decrement (B-134) was
  what kept its live updates working. Running the consumer on the leader needs the shared bus first, so
  this item is blocked by [B-136](B-136-the-realtime-bus-goes-through-kesh.md) and stays on its branch.

## Iteration 3 — 2026-10-06: on the shared bus

- Rebased on [B-136](B-136-the-realtime-bus-goes-through-kesh.md). The stand came up in exactly the
  arrangement that failed CI's e2e — `server-declining` leading `singletons` (relay and usage
  consumer), `server` leading `simulator` — and `make e2e` passed: the consumer's push now reaches the
  other server's SSE clients through kesh. `./gradlew build` green on the Linux box.
