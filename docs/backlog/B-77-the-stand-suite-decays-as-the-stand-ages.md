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

## What is accumulating

The traffic simulator logs where it is when it starts:

```
DEV ONLY — traffic simulator starting on partition 0 from offset 11605
```

Both failing scenarios wait for a counter belonging to a subscriber THEY just created to move. A
simulator walking subscribers in order reaches a new one later and later as the stand fills up, and a
day of manual walking through the app leaves dozens of them. The waits are 45 and 90 seconds; the
queue outgrew them.

That is a hypothesis with one piece of evidence — the offset, and the fact that a wipe fixes it. It
has not been measured against subscriber count.

## Why it is worth an item

Not because the tests are wrong: on a clean stand they are right, and the stand is meant to be torn
down. It is worth an item because of what it looks like from the outside. A suite that goes red on a
commit that changed nothing, on a stand nobody thought about, is a morning spent looking for a
regression that is not there — and the failure says "waited 45s", which reads like the product being
slow.

## What would fix it

Something that makes the state visible rather than something that hides it. Options, in the order
they seem worth trying:

- the scenario states what it waited for and how far behind the simulator was, so the message names
  the cause instead of the symptom;
- the simulator prefers subscribers with open streams, or a scenario nudges the counter it is waiting
  on directly, so the wait does not depend on a queue;
- `make e2e` refuses, or warns loudly, on a stand older than some age — the crudest of the three and
  the only one that needs no thought about the simulator.

## Anchors

| What | Where |
|---|---|
| The scenarios | `e2e/src/test/kotlin/io/konekt/e2e/LiveUpdateScenarioTest.kt`, `RoamingScenarioTest.kt` |
| The simulator | `server/src/main/kotlin/io/konekt/mocks/traffic/` |
| The stand | `Makefile` (`stand-up`, `stand-down`, `e2e`) |
