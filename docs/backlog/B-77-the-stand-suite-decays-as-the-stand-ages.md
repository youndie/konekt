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

## Measured, and the subscriber count is not the cause

`probes/lag.sh` creates subscribers with counters, then times how long a BRAND NEW one waits for its
data counter to move — which is what both failing scenarios are waiting for. Three readings per point,
because one is an anecdote and there is a broker in this.

| subscribers | run 1 | run 2 |
|---|---|---|
| 3 | 11, 17, 15 s | 13, 15, 16 s |
| 23 | 18, 15, 17 s | 15, 14, 17 s |
| 43 | 11, 16, 16 s | 16, 17, 14 s |
| 83 | 14, 18, 14 s | 13, 15, 17 s |

**Flat.** Every reading between 11 and 18 seconds, at every load, in both runs. And the two scenarios
that failed pass on that same stand at 83 subscribers.

So the hypothesis this item was filed with is refuted, and so is the one that replaced it: neither
"the simulator queues subscribers" nor "production outruns consumption" survives the numbers.

**Run 1 was nearly thrown away and is reported because it agrees, not because it is sound.** After it
finished the box turned out to have rebooted mid-measurement — `uptime` was seven minutes against a
run of thirty. That is why run 2 carries the machine's uptime on every line: 491 s at the start and
688 s at the end, monotone, one boot. A measurement whose harness restarts underneath it is not a
measurement, and the only reason to trust the first one at all is that the second reproduces it.

## What is left, and it is time rather than load

The stand that failed had been **up five hours**. This one was four minutes old and heavily loaded,
and it was fine — so what accumulates is not the number of subscribers but something that grows with
elapsed time. The candidates, none of them measured:

- **Postgres row bloat.** The simulator UPDATEs three counter rows per subscriber every five seconds
  and never stops. Five hours of that is a great many dead row versions on the one table every one of
  these scenarios reads.
- **The broker's log**, which nothing truncates.
- Something outside the product entirely — the box's own load, its disk.

The soak that would settle it: leave a loaded stand alone for an hour and re-run the two scenarios,
changing nothing else. It has not been run.

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
- **~~The simulator's output stops scaling with the subscriber count~~** — struck out by the
  measurement above. Eighty-three subscribers cost nothing.
- **`make e2e` says how old the stand is.** The crudest, and the only one that needs no theory about
  the simulator at all.

## Anchors

| What | Where |
|---|---|
| The scenarios | `e2e/src/test/kotlin/io/konekt/e2e/LiveUpdateScenarioTest.kt`, `RoamingScenarioTest.kt` |
| The simulator | `server/src/main/kotlin/io/konekt/mocks/traffic/` |
| The stand | `Makefile` (`stand-up`, `stand-down`, `e2e`) |
