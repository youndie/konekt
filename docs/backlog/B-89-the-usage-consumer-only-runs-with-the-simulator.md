---
id: B-89
title: "The only consumer of the usage topic is constructed inside the simulator's own starter, so reading real usage means also inventing some"
status: done
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

## What was done

`UsageChain` is a starter of its own and runs whenever the application does; `TrafficChain` starts the
simulator alone, behind `SIMULATE_TRAFFIC`. The consumer is the product's own worker — it applies
whatever arrives on a topic the deployment owns — and the simulator is a mock, and a switch that
turned both on together meant a deployment could not read real usage without also inventing some.

**The starting offset moved with it, and the limitation is now stated rather than implied.** Both
cases are named in `UsageChain`: from the end for a simulated feed, and for a real one the correct
default is the opposite — but booblik keeps **no consumer offsets**, so "where we left off" is not
something the broker can be asked. It would have to be a position this application stored itself. So
the honest sentence is the one now in the code and in `konekt-broker.md`: **usage published while this
process is down is not applied when it comes back.**

## Verified

- A new case in `TrafficChainTest`: an event published **by hand**, with no simulator anywhere near
  it, moves the counter and reaches an open stream — through `BrokerConnection` pointed at the
  harness's broker, so what is exercised is the assembly that ships. **Proved by mutation**: a
  `UsageChain.start` that returns a bare `Job` fails it with *nothing reached the open stream*.
- `WorkersAreStartedTest` now fails when the consumer's start call is deleted. That took four
  attempts and the story is below.
- `./gradlew check` green, 34 e2e green, `make check` green.

### `WorkersAreStartedTest` was passing on a worker nothing started, four ways

AC2 asks that the guard fail if the consumer is not started. It did not, and each fix revealed the
next — all four measured by deleting the start call and re-running, never reasoned about:

1. **An import is a mention.** The check searched the composition root for the class NAME, and
   `import io.konekt.mocks.traffic.UsageChain` is a mention.
2. **A binding is a mention.** `single { UsageChain(get(), …) }` is another one, on the line above the
   start call.
3. **A comment is a mention.** With both excluded, `TrafficChain`'s comment explaining the split named
   `UsageChain` in a sentence — and `TrafficChain` is reachable, so the name was present. The check
   now matches a **call**: `get<X>().start(` or `X( … ).start(`, the second bounded by "with no other
   `.start(` in between".
4. **A declaration looks like a construction.** `class UsageChain(` matched `X(` and the lazy tail ran
   forward to a *different* worker's `.start(` further down the same file. Excluded with `(?<!class )`.

And under all four sat the mechanism: **`Application.kt` was appending itself to the closure.** Its
first `^class …(` is `RouteGroup`, which its own text names, so the walk re-admitted the composition
root verbatim — imports, bindings and all — undoing every exclusion applied to it. The root is now
marked as already taken before the walk begins.

What the guard actually caught before this was a worker mentioned *nowhere*, which is a narrower
failure than the one it is named for. It has been in the tree since `B-16`, which exists because two
workers were written, covered end to end, and constructed by nothing.

## What is deliberately not in scope

Making the feed realistic — rates are tuned for a demonstration, per
[B-16](B-16-traffic-simulator.md) — and giving the dead-letter path an operator surface.

## Anchors

| What | Where |
|---|---|
| The product's worker | `server/src/main/kotlin/io/konekt/mocks/traffic/UsageChain.kt` |
| The mock, alone now | `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficChain.kt` |
| Where both are started | `server/src/main/kotlin/io/konekt/Application.kt` |
| The guard, four times over | `server/src/test/kotlin/io/konekt/di/WorkersAreStartedTest.kt` |
| What runs per replica | `docs/services/konekt-server.md` §5a, `docs/services/konekt-broker.md` |
