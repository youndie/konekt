---
id: B-108
title: "The usage consumer starts a megabyte from the beginning of the log and replays the rest"
status: done
priority: P1
size: S
stage: stage-m7-completeness
---

# B-108 — The comment said the end. The code said one poll in from the start.

`UsageChain.start()` carries a long, correct paragraph about where a consumer must begin:

> For a SIMULATED feed it is the only sensible answer: replaying a day of invented usage on every
> restart would empty every counter in the product.

And under it, until now:

```kotlin
val from =
    Consumer(connection.connection, TopicName(EventTopics.USAGE), partition).let {
        it.poll()
        it.position
    }
```

A consumer at offset **zero**, polled once. `position` then sits one `Consumer.DEFAULT_MAX_BYTES`
— one megabyte — in from the **start** of the log, which is a different number from the end of it.

## Measured, not inferred

On the stage deployment, from the two log lines side by side:

```
io.konekt.usage.chain  - usage consumer starting on partition 0 from offset 11915
konekt-broker          - usage-0: offsets 0..374473, 1 segment(s), 33554356 bytes
```

**11915 against 374473.** Every restart of that server replayed 362,558 historical usage events
against live counters — the exact thing the paragraph above says must not happen, written correctly
and implemented backwards.

## And it was about to stop working altogether

[B-100](B-100-the-broker-keeps-every-event-for-ever.md) turned retention on. Retention moves the
log's **start** above zero, and a fetch below the start is `OFFSET_OUT_OF_RANGE` — so the first boot
after the first segment deletion would have thrown out of `start()` and left the process with no
usage chain at all. A latent defect with a date on it.

## Why nothing caught it for two seasons

Every other test of this chain publishes **after** the consumer starts. That is the one arrangement
in which "one poll in from the start" and "the end" are the same number, because the log is short
enough for a single poll to reach the end of it. It took a 141 MiB log to tell them apart.

The same expression had spread to three tests, which found "where the topic already stands" the same
way. They were invisible for the same reason and are fixed with the same call.

## What was done

`from` comes from METADATA — `PartitionInfo.highWatermark` — in the same call that already asks which
partition to read. It cannot read a record, it cannot be out of range, and it is one round trip
instead of a wasted megabyte at every startup.

## Proved by mutation

`TrafficChainTest > a chain starting on a log that already has records applies none of them` publishes
**before** the chain starts, then one event after, and asserts the counter moved by that event alone.

| Mutation | Result |
|---|---|
| control | green |
| `from = Offset.ZERO` | FAILED |
| `from` = the original poll-once-from-zero expression | FAILED |

Three things were needed to make that test able to fail at all, and each is worth more than the fix:

- **Its own broker.** More than a megabyte of padding on a shared `usage` topic is not padding, it is
  pollution — it broke `BrokerTopicsTest`, which read its own probe back and got a filler record.
  `BrokerHarness.isolated()` exists for this one test.
- **A precondition, asserted.** The test states that one poll from zero does **not** reach the end of
  the log, and fails when it does not hold. The first padding — 24 records of 48 KiB, comfortably over
  a megabyte on paper — did not satisfy it, and without this assertion the test would have passed over
  the very defect it was written for.
- **The broken expression written out in full** at that assertion rather than called through the
  helper. A regex that replaced every instance of the shape replaced the probe too, and the assertion
  then compared the end against the end and passed regardless.

## Anchors

| What | Where |
|---|---|
| The line | `server/src/main/kotlin/io/konekt/mocks/traffic/UsageChain.kt` |
| The guard | `server/src/test/kotlin/io/konekt/mocks/TrafficChainTest.kt` |
| The isolated broker | `server/src/test/kotlin/io/konekt/events/BrokerHarness.kt` |
| What made it urgent | [B-100](B-100-the-broker-keeps-every-event-for-ever.md) |
| Found while doing | [B-107](B-107-a-smaller-segment-truncates-the-log-and-wedges-the-consumer.md) |
