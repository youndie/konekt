---
id: B-91
title: "A second replica silently loses live updates, and the only guard in the chart is about the simulator"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-91 — The realtime bus is in-memory and nothing says so where it would be discovered

`Application.kt` binds `single { KompotUpdateBroadcaster() }` — kompot's default, in-memory bus,
which is correct for one instance and is the whole of what this deployment has. The comment beside it
names the alternative (`kompot-realtime-redis`) and states the choice.

The consequence is not in any document a person deploying this reads. With two replicas:

- an SSE subscriber is attached to **one** pod, and a push produced by an HTTP request handled on
  **another** pod reaches nobody. The screen does not refresh, no error is logged, and the next
  ordinary screen fetch shows the correct state — so the symptom is "updates sometimes do not
  arrive", which is the hardest kind to attribute;
- `charts/konekt/values.yaml` defaults `server.replicas: 1` and `templates/server.yaml` fails the
  render only for `simulateTraffic && replicas > 1`. Raising replicas with the simulator off renders
  happily and degrades quietly.

The mirror case is already handled well elsewhere and worth pointing at: the suspended-saga sweeper
also runs per replica, and [B-64](B-64-a-rollback-refunds-once-per-replica.md) closed the money
consequence with a unique index rather than with a comment. Live updates have no such invariant to
lean on — a lost update is lost by design ([B-15](B-15-sse-realtime.md)).

- **The decision: refuse the render above one replica unless a shared bus is configured, and say why
  in the chart's values.** A default that is right and a failure mode that is silent is exactly the
  combination this repository fails builds over.
- **Not to adopt redis in this build.** One instance is the honest configuration for a reference,
  `kompot-realtime-redis` is a dependency and an operational surface, and the toolkit's own reason
  for pub/sub without guarantees — an update is losable because the next screen fetch carries current
  state — applies here.
- **So the deliverable is a guard and a sentence, not a feature.** The chart names the condition,
  `konekt-server.md` and `konekt-broker.md` state what scales and what does not, and
  [B-80](B-80-the-non-goals-are-nowhere.md) carries horizontal scale as a non-goal.
- This item does **not** cover the consumer's behaviour above one replica — that is
  [B-89](B-89-the-usage-consumer-only-runs-with-the-simulator.md), which is what makes the consumer
  startable independently in the first place — and does not revisit the sweeper's claim,
  [B-92](B-92-the-sweeper-still-does-not-claim-a-saga.md).

- AC: `helm template` fails when `server.replicas > 1` with no shared realtime bus configured, and
  the message names the bus rather than the count.
- AC: `konekt-server.md` states which components are per-replica and what each one does when there
  are two — one line each, from the code.
- Anchors: `server/src/main/kotlin/io/konekt/Application.kt`,
  `server/src/main/kotlin/io/konekt/realtime/RealtimeRouting.kt`,
  `charts/konekt/values.yaml`, `charts/konekt/templates/server.yaml`,
  `docs/services/konekt-server.md`.

## What was done

`charts/konekt/templates/server.yaml` refuses to render above one replica, and the message names what
would actually break rather than the count.

**Two things, not one.** The item is about the realtime bus, and writing the guard turned up the
second: booblik keeps no consumer offsets and there is no consumer group, so **every pod applies every
usage event** — a 25 MB decrement becomes 50 MB with two of them. Both are silent, and neither is a
count problem, so the message says what each would need: a shared bus (`kompot-realtime-redis`) for
the first, a claimed partition or a stored offset for the second.

**Refused outright rather than gated on a `sharedBus` setting, which is a deviation from the item's
wording and the honest form of it.** This build reads no such setting, so a key that let the render
through would be a switch that changes nothing while promising to — and a deployment that set it would
get exactly the silent failure the guard exists to prevent. What it would take is named in the message
instead.

**AC2 was already done**, in `B-89`: `konekt-server.md` §5a is a table of all five workers and what
each does with two pods, and the chart now refuses the combination that table describes.

### The guards were never run by anything

The chart refuses a render **five** ways — a missing database password, a missing image version, a
missing hostname, the simulator above one replica, and now any replica count above one — and nothing
in this repository ever rendered it. No CI job, no make target. Every one of those `fail` directives
had only ever fired in front of whoever was deploying.

So `scripts/chart-check.sh` renders the valid configuration and each refused one, and checks that each
refusal names **its own reason**. That second half is the point: a template broken by an unrelated
typo refuses everything, and would have satisfied every negative case. `make chart` runs it and CI
runs `make chart`.

## Verified

```
ok    the ordinary single-instance deployment renders
ok    two replicas is refused, naming "single-instance deployment"
ok    the simulator above one replica is refused, naming "simulateTraffic is on"
ok    a missing database password is refused, naming "postgres.password is required"
ok    a missing image version is refused, naming "server.version is required"
ok    a missing hostname is refused, naming "hostname is required"
```

## What is deliberately not in scope

Adopting redis. One instance is the honest configuration for a reference, `kompot-realtime-redis` is a
dependency and an operational surface, and the toolkit's own reason for pub/sub without guarantees —
an update is losable because the next screen fetch carries current state — applies here. Horizontal
scale is a non-goal in [reference-scope](../services/reference-scope.md).

The sweeper's duplicated work is [B-92](B-92-the-sweeper-still-does-not-claim-a-saga.md) and is
unaffected: its outcome is already correct under any number of replicas.

## Anchors

| What | Where |
|---|---|
| The guard | `charts/konekt/templates/server.yaml`, `charts/konekt/values.yaml` |
| What exercises it | `scripts/chart-check.sh`, `Makefile` (`make chart`), `.github/workflows/check.yaml` |
| What runs per replica | `docs/services/konekt-server.md` §5a |
| The bus it names | `server/src/main/kotlin/io/konekt/Application.kt` (`KompotUpdateBroadcaster`) |
