---
id: B-127
title: "The JVM is bounded by nothing but the container limit, and the limit is a gigabyte"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-127 — The JVM is bounded by nothing but the container limit

`charts/konekt/values.yaml` gives the server `1Gi` and says nothing else about memory, so every
ceiling inside the process is whatever HotSpot's ergonomics infer from that number: a heap of a
quarter of it, a code cache reserving 240 MB, direct memory equal to the heap, a megabyte of stack
per thread. The contour's pod sits at about 400 MiB of working set doing nothing, and the twelve-hour
soak (`research-measurements.md` §2) recorded the server climbing from 186 to 248 MiB and wrote the
climb down as **unresolved** — twelve hours cannot separate a heap settling towards a 247 MB ceiling
from a leak. A heap with a ceiling it can actually reach is what turns that question into an answer.

Three other Kotlin/JVM services on this stack already run a clamped JVM — `-XX:+UseSerialGC
-Xmx64M -XX:MaxMetaspaceSize=80M -XX:ReservedCodeCacheSize=32M -XX:MaxDirectMemorySize=32M
-Xss256k` under a 320Mi limit — and one of them has a load study behind those numbers. konekt is the
one with a measurement stand of its own, so it is the one where the recipe can be checked against
the service it is meant to bound rather than copied into it.

- **The decision and its reason.** Adopt the recipe, but take every number from a measurement on
  this server rather than from the neighbours, and keep the two numbers that come out different.
  `MaxMetaspaceSize` is **128M and not the 80M of that recipe**: this image carries an AOT cache, so class metadata is
  mapped from it and metaspace holds about 7 MiB — but a cache the JVM refuses (`B-31`, in this
  cluster, once already) loads those classes the ordinary way, and a ceiling sized for the good case
  turns a silent rejection into `OutOfMemoryError: Metaspace`. `ReservedCodeCacheSize` is **48M and
  not 32M**: 40 MB was committed under the reading profile at 200 rps, and a cap under what the JIT
  is using buys recompilation on a pod with one core rather than memory.
- **The control is part of the measurement, and it is what changed the answer.** A 256Mi limit alone
  makes the ergonomics pick the Serial collector, which is half of what the first flags ask for — so
  the sweep measures `limit-only` as its own variant, or this item would publish the clamp's effect
  while the limit did the work. What came out is that the limit alone makes the service WORSE: the
  heap is sized off `MinRAMPercentage` at that limit, so it goes up to 125 MiB committed against the
  unbounded JVM's 90, the peak lands at 250 MiB of 256, and the longest GC pause is 260 ms against 5.
- **What this item does not do.** It does not touch `-XX:+UseCompactObjectHeaders`, which would
  change object layout and therefore has to be symmetric with the AOT training run — the training
  happens in CI through `scripts/aot-image.sh`, which sees no chart value, so a flag set only in the
  chart would silently invalidate the cache. That is a separate item with a separate experiment.
- **Rejected: lowering `resources.limits.memory` and nothing else.** It is one line and it does bound
  the heap, and it leaves the code cache, direct memory and the thread stacks sized by a default that
  has nothing to do with this deployment — the three the measurement shows are worth 70 MiB between
  them.

- AC: the ceilings are set in `charts/konekt/values.yaml`, reach both the server and the migration
  container, and are the same in `deploy/compose.yaml` — paired by a test, because a stand whose JVM
  differs from the deployment's cannot find what the deployment will.
- AC: the chart refuses to render when `resources.limits.memory` is below what `server.jvmOptions`
  already promises the JVM, and `chart-check.sh` exercises that refusal.
- AC: `make e2e` passes against a stand running the clamp under the chart's limit — including the
  migration, which runs first and on the same ceilings.
- AC: the numbers are a measurement with its record: idle and peak anonymous memory, cold start and
  the reading profile's latency, for the unbounded JVM, for the limit alone and for the clamp, in
  `docs/research/measurements-2026-09-17/memory/`.
- Anchors: `konekt/charts/konekt/values.yaml`, `konekt/charts/konekt/templates/_helpers.tpl`,
  `konekt/deploy/compose.yaml`, `konekt/scripts/measure/memory.sh`,
  `konekt/server/src/test/kotlin/io/konekt/MemoryCeilingsTest.kt`

**Shipped in `konekt#47`, chart 0.6.0.** The ceilings reach both containers, the render refuses a
limit below them, `MemoryCeilingsTest` pairs the chart with the stand, and CI's `e2e` job ran the
whole suite against a stand carrying them — migration included.

**Two things this item did NOT finish**, and they are the reason the numbers above carry a caveat
rather than a bound:

- the sweep stopped at six runs of twelve when the build box left the network, so each clamped
  variant has ONE round against the unbounded JVM's two;
- no run with `-XX:AOTMode=off`, which is what would price `MaxMetaspaceSize=128M` instead of
  reasoning about it from the archive's size.

Both are cheap to finish — `scripts/measure/memory.sh 3` with the sweep file, plus one variant — and
whoever quotes 196 MiB as a bound should finish them first.
