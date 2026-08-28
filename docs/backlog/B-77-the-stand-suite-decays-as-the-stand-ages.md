---
id: B-77
title: "Two stand scenarios fail on a stand that has been up all day, and pass on a fresh one"
status: open
priority: P2
size: S
stage: stage-m4-proof
epic: feature-observability
---

# B-77 — The same commit, red at five hours old and green at one minute

`LiveUpdateScenarioTest` and `RoamingScenarioTest` both failed after a day of work on one stand:

```
a counter that moves reaches an open stream     — timed out waiting for 90000 ms
first use abroad starts it, and the screen …    — waited 45s for the package to start counting
```

Nothing server-side had changed since they last passed — the working tree held client files only —
and `make stand-down && make stand-up && make e2e` was green on the same commit, twice over.

## What is accumulating — the first answer here was wrong

This item first said the simulator "walks subscribers in order" and so "reaches a new one later and
later as the stand fills up". **It does not.** `TrafficSimulator.tick` publishes for EVERY subscriber
holding a counter, every interval, three events each:

```kotlin
val ids = subscribers()
ids.forEach { subscriberId -> usageAmounts.forEach { (kind, units) -> topic.send(…) } }
```

A subscriber created a second ago is in the very next tick. There is no queue of subscribers to be at
the back of.

The offset the simulator logs on startup — `from offset 11605` — was the evidence for that story, and
it does not support it: it says a great many events had been published, which is equally true of the
explanation below.

## The better candidate, read rather than measured

**Production scales with the number of subscribers and consumption does not.** The simulator sends
three events per subscriber every five seconds — 0.6 events per second per subscriber — while
`UsageConsumer` polls every 200 ms and applies each record with a database write and a realtime push.
Once 0.6 × N exceeds what the consumer sustains, the backlog grows without bound, and the wait for
any one subscriber's counter to move grows with it. Both failing scenarios wait for exactly that.

What is NOT established: the consumer's actual throughput, and therefore where the crossing point is.
Everything above is read out of two files.

**How to settle it**, and it needs the stand rather than more reading: create N subscribers with
counters on a fresh stand and measure the delay between a new subscriber appearing and their data
counter moving, for N of 0, 20, 40, 80. If the lag is flat, the explanation above is wrong too. The
measurement was written and not run — the build machine went off the network before it could be — so
this item stays open with a hypothesis rather than a cause.

## Why it is worth an item

Not because the tests are wrong: on a clean stand they are right, and the stand is meant to be torn
down. It is worth an item because of what it looks like from the outside. A suite that goes red on a
commit that changed nothing, on a stand nobody thought about, is a morning spent looking for a
regression that is not there — and the failure says "waited 45s", which reads like the product being
slow.

## What would fix it

Deliberately not decided until the measurement above exists: the second and third options below only
make sense if the cause is the one now suspected, and the first is worth having either way.

- **The scenario says what it was waiting for and how far behind the chain was.** "waited 45s" reads
  like the product being slow; "waited 45s, and the consumer was 900 events behind" names a cause.
  Worth doing whatever the answer turns out to be.
- **The simulator's output stops scaling with the subscriber count** — a cap per tick, or only
  subscribers seen recently. It publishes for everyone because the demonstration wants every screen
  to move; a stand with eighty abandoned subscribers wants no such thing.
- **`make e2e` says how old the stand is.** The crudest, and the only one that needs no theory about
  the simulator at all.

## Anchors

| What | Where |
|---|---|
| The scenarios | `e2e/src/test/kotlin/io/konekt/e2e/LiveUpdateScenarioTest.kt`, `RoamingScenarioTest.kt` |
| The simulator | `server/src/main/kotlin/io/konekt/mocks/traffic/` |
| The stand | `Makefile` (`stand-up`, `stand-down`, `e2e`) |
