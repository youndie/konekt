---
id: B-77
title: "Two stand scenarios fail on a stand left up for hours, and the hour-long soak was too short"
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

## Time does not reproduce it either

The loaded stand was then left alone for an hour and the two scenarios re-run, changing nothing else.
Machine uptime 812 s at the start and 4145 s at the end — one boot, 56 minutes, 83 subscribers
publishing throughout, on the order of 160,000 events produced and applied. **Both pass**, and so does
the whole suite.

That is more traffic than the stand which failed could have accumulated in its five hours, so the
"something builds up" story has now failed in both dimensions this machine can produce: load, and an
hour of time under load.

## Reproduced — and closing this was premature

It happened again, on a stand whose broker and database had been up **twelve hours** with **94
subscribers**. The failure message said so itself, because the load line added while chasing it was
already in place:

```
  the stand right now:
    broker running Up 12 hours (healthy)
    postgres running Up 12 hours (healthy)
    server running Up 3 minutes (healthy)
    the traffic simulator is publishing for 94 subscribers
```

Tearing that stand down and putting it back up, **same commit**, made both pass. So it is not the code
and it is not the working tree; it is the stand, and the variable is TIME with load beside it.

**The hour-long soak was simply too short.** 83 subscribers for one hour: fine. 94 for twelve hours:
red. Both earlier failures were on stands that had been up five hours and twelve. This item was closed
on a soak an order of magnitude shorter than the condition it was trying to reproduce, which is the
mistake worth naming: a negative result at one hour says nothing about twelve, and I wrote it up as
though it did.

**The host-reboot story is downgraded, not deleted.** The machine did reboot twice that evening, and
that remains a possible contributor to the FIRST occurrence. It cannot explain this one: the box has
been up throughout and everything else on it works.

## What is ruled out, and what is not

**Ruled out by measurement:**

* *The simulator queues subscribers.* It does not — `tick` publishes for every subscriber with a
  counter, every interval. Read in the source.
* *Production outruns consumption, as a function of load.* The lag from a new subscriber to their
  counter moving is flat from 3 to 83 subscribers — 11 to 18 seconds, three readings a point, two
  runs. `probes/lag.sh`.
* *One hour of a loaded stand is enough to cause it.* It is not.

**Not ruled out, and now the whole of the suspicion:** something that accumulates over many hours.
The first candidate is `usage_counter` — the simulator UPDATEs three rows per subscriber every five
seconds and never stops, so a stand left overnight holds an enormous number of dead row versions on
the one table all three failing waits read. Twelve hours at 94 subscribers is on the order of two
million updates. The broker's log is the second.

**Neither has been measured on a stand in the failing state**, and that is the thing to fix next
rather than another theory. Both times it reproduced, the stand was torn down within minutes — once
to check whether a fresh one was fine, which was the right question and destroyed the evidence
answering it.

So `standDiagnosis` now reports `usage_counter`'s live rows, dead rows and size on disk beside the
subscriber count. The next reproduction carries its own measurement, and nobody has to have thought
to look.

## What to do next

1. **Wait for it to reproduce and read the message.** It now carries the subscriber count and the
   table's dead-tuple count. Two reproductions with those numbers settle the bloat question without
   another soak.
2. If it is bloat: the simulator should not UPDATE a row every five seconds forever — or `usage_counter`
   wants an autovacuum setting a demonstration stand can live with. Both are decisions, not fixes to
   guess at now.
3. If it is not: the broker's log is next, and the same rule applies — measure it in the failure.

**Do not close this on a soak again** unless the soak is at least as long as the condition. An hour
against twelve is not evidence of absence.

## What the chase has produced so far

No fix, and three mechanisms ruled out in writing so nobody proposes them again. Plus the two things
that made the second reproduction legible in ten seconds instead of a morning: `probes/lag.sh`, and a
failure message that now names the stand's age, its load and the state of the table underneath it.

## Anchors

| What | Where |
|---|---|
| The scenarios | `e2e/src/test/kotlin/io/konekt/e2e/LiveUpdateScenarioTest.kt`, `RoamingScenarioTest.kt` |
| The simulator | `server/src/main/kotlin/io/konekt/mocks/traffic/` |
| The stand | `Makefile` (`stand-up`, `stand-down`, `e2e`) |
