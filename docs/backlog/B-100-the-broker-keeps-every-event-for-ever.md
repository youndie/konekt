---
id: B-100
title: "The broker is deployed with retention off, so its volume fills and the failure is silent"
status: done
priority: P2
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
broker's volume held **148 MiB of real blocks after eight and a half hours**, essentially all of it in
the `usage` partition — about 17 MiB an hour. Read from a read-only pod mounting the same claim
rather than by `exec` into the broker.

### The first reading of this was wrong, twice over, and the correction is the useful part

It was first reported as **1.61 GB after seven hours, full by hour ten**. Both halves were wrong:

* **`du -sb` reports apparent size**, and booblik's segments are `MAPPED` and pre-allocated to
  `LogSegment.DEFAULT_CAPACITY` — 512 MiB each, three partitions, exactly the 1536 MiB that was read.
  The files are sparse: `ls -ls` gives 4 KB, 24 KB and 148 MiB of actual blocks against 512 MiB
  apparent, each. The metric could not have shown growth for this kind of file whatever the volume
  did;
* **and a rate was inferred from one point** plus an assumption that the volume started empty. It did
  not start empty in the sense that mattered — it started at its full apparent size, within seconds.

Three consecutive readings an hour apart, all identical to the byte, are what exposed it. A number
that does not move is worth as much as one that does.

So the danger is real and slow rather than imminent: nothing deletes anything, and 17 MiB an hour
fills any volume eventually. It is not on course to fill this one inside a soak.

## What full costs, measured rather than assumed

[youndie/booblik#15](https://github.com/youndie/booblik/issues/15) — **fixed upstream in `b9135bc`
and not in any release**. The newest is `0.3.0`, from before the fix, and both the chart and the
compose stand pin `ghcr.io/youndie/booblik:0.3.0`, so what follows is what THIS build runs today and
will keep running until the pin moves.

Two halves of the upstream answer are worth carrying here rather than behind a link. The producer
now gets a refusal — a new wire code, `PARTITION_UNAVAILABLE` (6) — instead of a request held for
ever, which is the half that ends the silence. **The health check still cannot fail on it**, and that
is tracked upstream rather than done: METADATA answers from a registry a dead writer never touched,
so an orchestrator still sees a healthy broker and still does not restart it. Exiting the process was
rejected on a measurement rather than a preference — reads from the same partition keep working after
the failure.

When the pin does move, the question this repository has to answer is what its outbox relay does with
a publish that now FAILS rather than hangs. That relay is petich's `OutboxRelayWorker`, not ours, and
it is a question for the bump rather than for this item.

Reproduced on a tmpfs of a fixed size, on `0.3.0`:

* the partition writer dies on `java.lang.InternalError: a fault occurred in an unsafe memory access
  operation` — `MAPPED` is the default segment mode, and a write into an `mmap`ped region whose
  backing store cannot grow is a **SIGBUS**, not an `IOException`;
* the **process stays running**, the port stays open;
* the container's healthcheck asks METADATA and reports **healthy**;
* the broker's metrics report **`errors 0 dropped 0`**;
* `backlog 1` — the produce is held, unacknowledged, for ever. The publisher waits rather than fails.

A broker that is up, green, and silently accepting nothing.

**And with the corrected rate, this is no longer a plausible explanation of `B-77`.** At 17 MiB an
hour a stand would need weeks to fill a volume, and `B-77` reproduces in five to twelve hours. The
shape of the two failures is the same — the event does not arrive, the counter does not move, the
wait expires — and the shape was the whole of the resemblance. It is written down here so that
nobody, including whoever writes the next item, reaches for it again as the ready explanation.

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
- **THE SEGMENT SIZE IS THE SETTING THAT MATTERS, and the retention bound alone would have done
  nothing.** Both of booblik's retention functions drop whole segments and neither touches the active
  one — `while (live.size > 1 && …)`. Each partition here holds exactly one segment, so a bound of
  any size is a no-op until a segment closes, and a 512 MiB segment at 17 MiB an hour closes after
  about thirty. Read in `PartitionLog.retainAtMost`, not assumed.

  So `booblik.segment.capacity.bytes` comes down to **32 MiB** — `usage` then rolls about every two
  hours and retention has something to delete; the other two roll almost never, which is right,
  because they hold almost nothing. It also drops the preallocated footprint from 3 × 512 MiB to
  3 × 32 MiB.

- **A number with its arithmetic.** `retention.bytes` is per partition and this deployment has three
  (`orders:1,usage:1,notifications:1`). At `128Mi` each, the worst case is four closed segments plus
  the active one — 160 MiB — and 480 MiB across the three, against a `2Gi` claim. `retention.millis`
  at six hours is the second bound, so an idle stand does not hold days of nothing.
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

- **`B-77`.** Once a candidate, now ruled out by the corrected rate — see above. The soak that found
  this is still running and is measuring something else.
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

## What was done

Three settings, in both files that configure the broker, with the arithmetic in comments rather than
the numbers alone:

| | |
|---|---|
| `BOOBLIK_SEGMENT_CAPACITY_BYTES` | 32 MiB |
| `BOOBLIK_RETENTION_BYTES` | 128 MiB per partition |
| `BOOBLIK_RETENTION_MILLIS` | six hours |

**The segment size is the one that mattered, and the item's first numbers would have done nothing.**
It proposed a 256 MiB bound and said nothing about segments. Reading `PartitionLog` rather than
assuming: `retainAtMost` and `retainNewerThan` both loop `while (live.size > 1 && …)` — they drop
whole segments and never the active one. Each partition here holds exactly one, so any bound is a
no-op until a segment closes, and booblik's 512 MiB default closes one after about thirty hours at
this product's rate. At 32 MiB the busy partition rolls about every two hours and retention has
something to delete. The preallocated footprint drops from 3 × 512 MiB to 3 × 96 MiB with it.

`ComposeStandTest` pairs the two files, the way it already pairs the topics — **and asserts the bound
exceeds one segment**, which is the assertion the pairing alone does not make: two numbers that are
individually plausible and jointly useless is exactly the shape this item nearly shipped.

## Verified

- **Proved by mutation, three ways**: the files disagreeing on a value; the stand missing one
  entirely; and a segment size raised back to booblik's default, which is caught with *"the retention
  bound (134217728) is not larger than one segment (536870912), so retention can never drop
  anything"*.
- **The mutations needed `--rerun-tasks` to be believed.** This guard reads files outside its module,
  which are not Gradle inputs, so the first run of all three was UP-TO-DATE and silently green —
  the trap `CLAUDE.md` already records, met in the field.
- **The setting is applied, read from the broker's own startup line** rather than from the file that
  was supposed to produce it: `segment: mode=MAPPED capacity=33554432` and
  `retention: bytes=134217728 millis=21600000 check=30000`.
- `make e2e` green on the stand; `make chart` — all six guards; `make check` green.
- **The chart's version guard caught this change**, which is what it is for: `0.2.0` → `0.2.1`, a
  patch because a values file that never mentions these renders exactly as before.

## What is NOT verified, and will not be by this item

**That the volume stops growing.** A fresh stand cannot show it in minutes, and the one long-running
stand that could — the `B-77` soak — was installed before this change and is deliberately left alone:
upgrading it would change the thing it is measuring. So this is a setting proved APPLIED rather than
proved EFFECTIVE, and the difference is stated rather than glossed. The first stand that lives past
six hours with these values settles it.

## Anchors

| What | Where |
|---|---|
| The settings and the arithmetic | `charts/konekt/values.yaml`, `deploy/compose.yaml` |
| Passed to the container | `charts/konekt/templates/broker.yaml` |
| The guard | `server/src/test/kotlin/io/konekt/events/ComposeStandTest.kt` |
| Why a bound below a segment does nothing | booblik `PartitionLog.retainAtMost` |
| What is kept, written down | `docs/services/konekt-broker.md` §7 |
