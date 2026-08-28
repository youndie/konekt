---
id: B-77
title: "Two stand scenarios failed once and could not be reproduced; the host was on its way down"
status: done
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

## What it most likely was, and the evidence is not about konekt

Within about two hours of those two failures the build machine **stopped answering** — no ping, every
mutagen session stuck — and came back having **rebooted**. It rebooted a second time during the first
lag measurement, which is why that measurement was repeated with the machine's uptime on every line.

A host that is on its way down is a much better explanation of two timeouts than anything measured
here, and it is the only explanation with independent evidence behind it. The stand was five hours
old; so was the machine's problem.

## What this item was wrong about, in order

1. **"The simulator walks subscribers in order."** It does not — `tick` publishes for every subscriber
   with a counter, every interval. Read.
2. **"Production outruns consumption."** Measured: the lag is flat from 3 to 83 subscribers.
3. **"Something accumulates with time."** Measured: an hour of a loaded stand changes nothing.

The offset the simulator logs on startup was quoted as evidence for the first, and supports none of
the three. It says events were published, which is true of every stand that has ever run.

## Closed as not reproduced, and two things stay

- `probes/lag.sh`, so the next person asking this question starts from a measurement rather than from
  a story. It carries the uptime because of what happened to the first run.
- `Stand.standDiagnosis` now reports how many subscribers the simulator is publishing for. A count and
  no verdict — it was invisible, and a number nobody can see is a number every theory can lean on.

Reopen if it happens again on a machine that stays up. One observation and three refuted mechanisms
is not a defect in this repository; it is a morning that was spent looking for one.

## Why it was worth the day anyway

Not for the fix — there is none. For what the chase produced: a measurement where there had been a
story, a probe that can be re-run, a failure message that now names the stand's load, and three
mechanisms ruled out in writing so nobody proposes them again.

And the rule it leaves behind, which is in `CLAUDE.md`: before chasing a stand failure, tear the stand
down and put it back up. If it survives that, it is a finding; if it does not, look at the machine
before looking at the code.

## Anchors

| What | Where |
|---|---|
| The scenarios | `e2e/src/test/kotlin/io/konekt/e2e/LiveUpdateScenarioTest.kt`, `RoamingScenarioTest.kt` |
| The simulator | `server/src/main/kotlin/io/konekt/mocks/traffic/` |
| The stand | `Makefile` (`stand-up`, `stand-down`, `e2e`) |
