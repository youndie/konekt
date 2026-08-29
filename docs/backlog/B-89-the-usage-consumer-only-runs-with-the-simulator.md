---
id: B-89
title: "The only consumer of the usage topic is constructed inside the simulator's own starter, so reading real usage means also inventing some"
status: open
priority: P1
size: S
stage: stage-m7-completeness
---

# B-89 — Two halves of a demonstration, welded into one switch

`TrafficChain.start()` builds both ends and starts them together:

```kotlin
val simulator = TrafficSimulator(producer = connection.producer, …)
val consumer  = UsageConsumer(connection.connection, consume, push, cards, roaming, roamingCards, clock, json)
return listOf(simulator.start(scope), consumer.start(scope, partition, from))
```

Nothing else constructs `UsageConsumer`. `TrafficChain` itself starts only under `SIMULATE_TRAFFIC`,
which is off by default and refused outright by the Helm chart above one replica. So with the flag
off, **no process in this build reads the `usage` topic at all** — the broker accepts events and
nobody applies them.

The welding was right when it was written: [B-16](B-16-traffic-simulator.md) exists because both
halves were built, tested and constructed by nothing, and putting them in one starter is what fixed
that. What has changed is the claim. The chain broker → consumer → counter → realtime → screen is
described in the source as *the same chain a real integration would use*, and a real integration
cannot use it: switching the consumer on switches on a fake producer that drains every subscriber's
allowance beside it.

The offset choice has the same shape. `TrafficChain` starts from where the broker is **now** and
says why — replaying a day of simulated usage would empty every counter. For a real feed the correct
default is the opposite one, and the comment says that too.

- **The decision: separate the two. `UsageConsumer` starts whenever the broker is configured;
  `TrafficSimulator` stays behind `SIMULATE_TRAFFIC`.** The consumer is the product's own worker —
  it applies whatever arrives on a topic the deployment owns — and the simulator is a mock.
- **`WorkersAreStartedTest` is what keeps the separation honest**, because a worker nobody starts is
  exactly the failure this repository has already had twice: a binding is data and can be verified,
  a `start(scope)` call is control flow and cannot.
- **The starting offset becomes a decision of the consumer rather than of the chain**, with the two
  cases named: from the end for a simulated feed, from the stored position for a real one — and
  since booblik keeps no consumer offsets, "stored" means stored by us or not offered at all. Say
  which, in the code.
- The rejected alternative is a second flag that starts the consumer alone. That is the same weld
  with an extra switch, and the default is still wrong.
- This item does **not** make the feed realistic — rates are tuned for a demonstration, per
  [B-16](B-16-traffic-simulator.md) — and does not give the dead-letter path an operator surface.

- AC: with `SIMULATE_TRAFFIC` off and a broker configured, an event published to `usage` by hand
  moves the counter and reaches an open stream.
- AC: `WorkersAreStartedTest` fails if the consumer is not started by the composition root.
- AC: the chart's guard still refuses `simulateTraffic` above one replica, and the consumer's own
  behaviour above one replica is stated in `konekt-broker.md` rather than left to be discovered —
  see [B-91](B-91-a-second-replica-loses-live-updates.md).
- Anchors: `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficChain.kt`,
  `server/src/main/kotlin/io/konekt/mocks/traffic/UsageConsumer.kt`,
  `server/src/main/kotlin/io/konekt/Application.kt`, `charts/konekt/templates/server.yaml`,
  `docs/services/konekt-broker.md`.
