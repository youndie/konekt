---
id: B-100
title: "The broker is deployed with retention off, so its volume fills and the failure is silent"
status: open
priority: P1
size: S
stage: stage-m7-completeness
---

# B-100 — Nothing ever deletes an event, and the end of that road is a healthy-looking broker

booblik has retention — by bytes and by age, implemented and tested — and it is **off unless it is
configured**:

```kotlin
if (config.retentionBytes == null && config.retentionMillis == null) return   // booblik Main.kt
```

konekt configures neither. `charts/konekt/templates/broker.yaml` and `deploy/compose.yaml` each set
`BOOBLIK_TOPICS` and nothing else, so every event this product has ever published is still on disk.

**This is not a booblik defect and the item should not be read as one.** The broker says what it is
doing on the line it prints at startup — `retention: bytes=null millis=null check=30000` — and a
durable log that deletes nothing by default is the safe default for a log. What is missing is a
decision on our side that was never made.

## Measured

On a soak stand (90 subscribers, the simulator publishing three events each every five seconds), the
broker's volume held **1.61 GB in 3 files after seven hours**, against a claim of `2Gi`. Read from a
read-only pod mounting the same claim rather than by `exec` into the broker.

At that rate the claim is full in about ten hours — inside the window where `B-77` reproduces.

## What full costs, measured rather than assumed

[youndie/booblik#15](https://github.com/youndie/booblik/issues/15), reproduced on a tmpfs of a fixed
size:

* the partition writer dies on `java.lang.InternalError: a fault occurred in an unsafe memory access
  operation` — `MAPPED` is the default segment mode, and a write into an `mmap`ped region whose
  backing store cannot grow is a **SIGBUS**, not an `IOException`;
* the **process stays running**, the port stays open;
* the container's healthcheck asks METADATA and reports **healthy**;
* the broker's metrics report **`errors 0 dropped 0`**;
* `backlog 1` — the produce is held, unacknowledged, for ever. The publisher waits rather than fails.

A broker that is up, green, and silently accepting nothing. That is the same shape as `B-77`'s
failures — the event does not arrive, the counter does not move, the wait expires — and it is worth
saying plainly that this is **a candidate for `B-77`, not its established cause**: this stand has not
reproduced `B-77`, and the volume filling is one road to that symptom rather than the road.

## The decision

- **Both bounds, in both files.** `booblik.retention.bytes` protects the claim; `.millis` keeps an
  idle stand from holding a day of nothing. Environment form is the property uppercased with dots
  replaced — `BOOBLIK_RETENTION_BYTES`, `BOOBLIK_RETENTION_MILLIS`.
- **Retention costs this product nothing in correctness**, and that is worth stating because it is
  what makes an aggressive value safe here. `UsageChain` starts its consumer **from the end of the
  log** — booblik keeps no consumer offsets, so "where we left off" is not a question the broker can
  answer — and the limitation is already written down: usage published while the process is down is
  not applied when it returns. Nothing in this build ever reads an old record, so deleting one
  changes no behaviour.
- **A number with its arithmetic.** `retention.bytes` is per partition and this deployment has three
  partitions (`orders:1,usage:1,notifications:1`). At `256Mi` each the worst case is `768Mi` against
  a `2Gi` claim — room for the index files and a partially-written segment, and far more history
  than anything here reads.
- **Paired across the two files by a test**, the way the topics already are. `ComposeStandTest`
  compares `BOOBLIK_TOPICS` in the compose file against `EventTopics`; retention set in the chart and
  forgotten in the stand is the same defect one file later.
- **Rejected: fixing this by growing the claim.** A bigger volume moves the date and keeps the
  failure. The point is that nothing should ever reach it.

## Acceptance criteria

- AC: both bounds are set in `charts/konekt/values.yaml` and `deploy/compose.yaml`, with the
  arithmetic in a comment rather than the numbers alone.
- AC: a test refuses a chart or a compose file that sets one and not the other, or neither — proved
  by mutation, both directions.
- AC: the chart's guards gain a case if the values are required rather than defaulted, so
  `scripts/chart-check.sh` covers it like the other five refusals.
- AC: measured on the stand — the broker's volume stops growing, read the same way it was read here
  (a read-only reader, not `exec`). A setting that is present and not working is exactly what this
  item is about.
- AC: `docs/services/konekt-broker.md` says what is kept and for how long, beside the sentence about
  there being no consumer offsets.

## Deliberately not in scope

- **`B-77`.** This may be one of its roads and is not established as its cause. The soak that found
  this is still running and will say more.
- **booblik's behaviour when full.** That is
  [youndie/booblik#15](https://github.com/youndie/booblik/issues/15), and konekt should not reach it
  either way.

## Anchors

| What | Where |
|---|---|
| The two files that configure the broker | `charts/konekt/templates/broker.yaml`, `deploy/compose.yaml` |
| The test that already pairs them on topics | `server/src/test/kotlin/io/konekt/events/ComposeStandTest.kt` |
| Why retention is free here | `server/src/main/kotlin/io/konekt/mocks/traffic/UsageChain.kt` (the consumer starts at the end) |
| What full looks like | [youndie/booblik#15](https://github.com/youndie/booblik/issues/15) |
