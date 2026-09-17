# What the JVM holds, and what clamping it costs — 2026-09-17

`scripts/measure/memory.sh` on the build box (Ubuntu 24.04 in WSL2, Core Ultra 7 255HX, 20 cores,
Docker 29.1.3), the stand from `deploy/compose.yaml` + `deploy/compose.measure.yaml`, image
`konekt-server:mem-base-aot` — the release shape: `deploy/Dockerfile` over `installDist`, with the
Leyden cache trained into it by `scripts/aot-image.sh`, on `eclipse-temurin:25-jre`. Workload:
`screens` at `RATES=25,200 HOLD=45 SUBSCRIBERS=30`. See `research-measurements.md` §9 and `B-127`.

| File | What |
|---|---|
| `memory.csv` | one line per run: cold start, `anon` at rest / peak / after, k6's p95, GC pauses, and the JVM's own NMT summary at exit |
| `nmt-{base,neighbour,konekt}-1.log` | the server's whole log for round 1 of three variants, the `-XX:+PrintNMTStatistics` summary at the end of each |

## What this record does not have

**Six runs of the twelve.** The sweep alternates four variants inside a round and was cut off in
round two when the build box dropped off the network. So `base` and `limit-only` have two rounds —
which agree to the megabyte on every memory column — and `neighbour` and `konekt` have one each. The
clamp's 196 MiB peak is therefore a single run, and rounds three and four of it are unfinished work,
not a formality: one run of a variant is not a measurement, and this repository has said so before.

**No run with the AOT cache refused.** `MaxMetaspaceSize=128M` is sized for the case where the JVM
rejects the cache and the 5 249 classes load into metaspace the ordinary way. That case was reasoned
about, not run — `-XX:AOTMode=off` under the clamp is the missing variant.

**The generator shares the box with the subject.** k6 gets its own cores but not its own machine, so
the p95 column is a smoke test for "the clamp did not break it" rather than a latency figure; the
latency numbers this repository quotes come from the two-box stand (`research-measurements.md` §1).

## Two columns that were wrong before they were right

**`memory.current` was the first metric and it is the page cache.** This image maps a 64 MiB AOT
cache, so by `memory.current` every variant approaches its limit while holding nothing. The column
here is `anon` from the container's own `memory.stat`. The load study of a neighbouring service on
this stack made the same mistake first.

**The `oom` column reads `2` for the clamped variants and it means nothing.** The JVM echoes
`JAVA_TOOL_OPTIONS` on its first line, `-XX:+ExitOnOutOfMemoryError` and all, once per container
start — and the grep counted the word. No run in this record ran out of memory: the logs beside it
are the evidence. The CSV is the raw output, wrong column and all, and `memory.sh` now excludes that
line — a record edited to look right is a record nobody can check.

## The runs

| variant | limit | ready ms | anon idle | anon peak | k6 p95 | GC pauses | longest pause | heap | code |
|---|---|---|---|---|---|---|---|---|---|
| `base` — today's chart, no flags | 1Gi | 5222 / 1733 | 110 / 113 | **230 / 230** | 4.1 / 3.4 | 281 | 7.6 / 4.6 ms | 90 | 37 |
| `limit-only` — 256Mi and no flags | 256M | 1624 / 1768 | 113 / 110 | **250 / 233** | 3.0 / 4.8 | 569 | 49.7 / **259.7 ms** | 125 | 37 |
| `neighbour` — the recipe the stack's other Kotlin/JVM services run | 320M | 1669 | 114 | **180** | 2.9 | 108 | 31.9 ms | 56 | 27 |
| `konekt` — the recipe, two numbers changed | 256M | 1719 | 116 | **196** | 3.0 | 110 | 48.3 ms | 62 | 32 |

Two rounds where two are given. Memory in MiB; `heap` and `code` are NMT's committed figures at exit.

**The control is the finding.** Lowering the limit and changing nothing else does not shrink this
service — it makes it worse on every axis that matters. HotSpot sizes the heap off
`MinRAMPercentage` at that limit, so the heap goes UP, to 125 MiB committed against the unbounded
JVM's 90; the peak lands at 250 of 256, and the longest GC pause is 260 ms against 5. Without this
variant in the sweep, the clamp's numbers would have been published with the limit doing the work.

**What the clamp costs.** GC pauses go from 5 ms to 48 at the tail, on a collector that was already
Serial in every variant — the chart's `cpu: 1` picks it. The p95 of a screen does not move: 3.0 ms
clamped against 3.4–4.1 unbounded, which is the box's own noise.

**What it buys.** 230 → 196 MiB at the peak and a heap that cannot follow the limit upwards. The
measured floor is lower than that: `neighbour`'s tighter code cache reached 180 MiB, and the reason this
deployment does not take it is that the same run shows 32 MiB of code cache in use — capping under
what the JIT is using buys recompilation on a one-core pod, not memory.

## Where the 196 MiB actually is

NMT at exit, clamped run (`nmt-konekt-1.log`), against the unbounded one beside it:

| | clamped | unbounded |
|---|---|---|
| Java heap, committed | 63 MiB | 91 MiB |
| Code | 32 | 38 |
| Thread | 9.5 | 10.2 |
| Metaspace | 6.98 | 6.99 |
| Shared class space (the AOT archive) | 58.8 | 58.8 |
| Total committed | **181** | **214** |

Three things follow, and the first is the one to remember.

**The AOT cache is accepted under the clamp.** 58.8 MiB of shared class space is mapped in both,
so `-XX:+UseSerialGC` and `-Xmx64M` did not invalidate a cache trained under G1 with the default
heap — the boundary is compressed oops and class pointers, not the collector (zavarnik's R10/R12).
It is also why metaspace is 7 MiB and not seventy: the class metadata is in the archive.

**`-Xss256k` moves reserved space, not resident.** Committed thread memory went from 10.2 to
9.5 MiB. It is the one flag in the recipe whose measured gain is smaller than its failure mode.

**About 100 MiB of the 196 is neither heap nor code cache**, and most of it is that archive: mapped
private, relocated on the way in, and therefore charged to this container as anonymous memory. That
is the number the chart's render-time guard uses as its headroom.
