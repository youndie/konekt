---
id: B-117
title: "What the stack costs, measured: load by RPS, a soak, and six things a green build does not show"
status: open
priority: P1
size: L
stage: stage-m7-completeness
---

# B-117 — Measurements

The README's claim is that this repository writes down **what each toolkit costs**. So far that is
counted, not measured: database writes per saga read off `pg_stat_statements`, a client release per
corner radius, a broker restart per topic. None of it says what the server does at fifty requests a
second, or after six hours. This item is the stand, the method and eight measurements, in an order
where each one either produces a number the README can carry or shows a defect that a number would
have hidden.

**The rule under all of it:** a figure that is not accompanied by *what was measured, on what, how
many times and against which baseline* is not a measurement, and does not go into a document.

## The stands

Two, because a soak and a load test want opposite things from a machine.

| | The load stand | The soak stand |
|---|---|---|
| **What for** | measurements 1, 3, 4, 5, 6, 8 — everything that pushes the server until something gives | measurement 2, and 7 alongside — hours at a constant, moderate rate |
| **Where** | a machine rented for the run and returned afterwards: **two** if the generator and the server are to be told apart (the generator on its own box, the stand on the other, a private link between them), one if the run is about ratios only | the owner's spare box outside this repository (its address is in the operator's notes, not here). Two cores, a few gigabytes, docker. **It already carries a light soak of booblik**, which stays: the two share the machine and the report says so |
| **What runs** | `deploy/compose.yaml` as the stand runs it today — postgres, booblik, the server, the declining server, metrik, tracy, katcher — with the **chart's limits** on the server (`cpu: 1`, `memory: 1Gi`, from `charts/konekt/values.yaml`) written into the compose override, so the number is about the deployment and not about a laptop | the same compose, the same limits, `SIMULATE_TRAFFIC=true` so the usage chain runs as it does on the test contour |
| **The generator** | [k6](https://k6.io), `constant-arrival-rate` executors — a rate is a rate, not a number of virtual users that happen to produce one | k6 too, one scenario, for hours |
| **The oracle** | **metrik on the stand**, per route, read after the run — the same path the test contour is observed through. k6's own timings are kept as a second column and the two are expected to agree within the generator's overhead; where they do not, that is a finding | metrik for latency; `docker stats` and `/proc` for RSS, descriptors and connections; Postgres's own `pg_stat_activity` and table sizes; the broker's offsets |

The stand is brought up by a script (`scripts/measure/stand-up.sh`, to write) that takes a fresh
Ubuntu box to a running compose with the limits applied, the k6 binary present and the scenarios
copied — so a rented machine costs an hour of rent, not a day. The scenarios live in
`scripts/measure/k6/`, one file per measurement, and each prints its own parameters at the top of
its output so a result cannot be read without them.

## The method, once

- **Warm up, then measure.** The first minute after a start is JIT and pools; it is recorded and
  excluded (first-run-after-restart measures warm-up, not the system).
- **Every point at least three times**, on the same stand, in the same session. A single run per
  variant is an anecdote. The report carries the spread, not only the middle.
- **Relative and absolute together.** Every comparison names the baseline and the ratio *and* the
  absolute figure; a ratio without an absolute decides nothing, and an absolute without its baseline
  cannot be compared to the next machine.
- **The axis is decided before the run**, and the run's verdict is on that axis. What saturated —
  CPU of the server, the Postgres pool, the broker — is read from the machine, not inferred from a
  latency curve.
- **What was measured is in the report.** Machine, limits, compose commit, server tag, k6 version,
  the scenario file, the date. A table asserts exactly what its caption says and nothing more.
- **Failures are counted by the collector and by the client both**, and a run where every request
  passes at every rate is a run that did not find the knee — it is extended until something fails,
  or reported as "no knee under N".

## The measurements

### 1. Load by RPS — the three profiles

Three scenarios, each a staircase of arrival rates held for a fixed window, up to the knee.

| Profile | What it exercises | Requests |
|---|---|---|
| **Reading screens** | kompot on the server, one query per screen, no writes | `GET /api/v1/screens/home`, `/plans`, `/plans/{id}` — signed-in subscribers, a pool of tokens minted before the run |
| **Buying** | the petich saga end to end: hold, provision, settle, outbox, booblik, the consumer | `POST /api/v1/purchases` then `POST …/{id}/confirm`, on subscribers topped up before the run, with the plan and the balance chosen so the saga completes |
| **Realtime** | the SSE channel and the usage chain | N subscribers holding `GET /api/v1/realtime` open while the simulator produces usage events — measured in 4 |

Per profile and per rate: p50 / p95 / p99 per route from metrik, error share, and the resource that
gave out (CPU of the server container, `pg_stat_activity` waits, broker lag). The knee is the rate
at which p99 leaves its plateau or errors appear, whichever first. **Decides:** the number the
README states, and whether the chart's `cpu: 1` is the right ask.

### 2. The soak

One scenario at a moderate constant rate — a fraction of the knee found in 1 — for hours, with the
simulator on, on the soak stand. Sampled every minute:

- the server's RSS and JVM heap (through metrik's system metrics and `docker stats`);
- open connections to Postgres (`pg_stat_activity`) and open SSE clients (the server's own gauge,
  to add if absent);
- file descriptors of the server process;
- GC pauses (the JVM's own log, `-Xlog:gc` into a file the compose mounts);
- outbox depth and the consumer's lag behind the broker's high watermark — the chain
  [B-107](B-107-a-smaller-segment-truncates-the-log-and-wedges-the-consumer.md) made survive a restart, watched
  for drift without one;
- the size of the outbox and history tables.

**Decides:** whether anything grows without bound, and by how much per hour. A flat line for six
hours is the result; a slope is a defect with its rate attached. The booblik soak sharing the box is
named in the report as a source of noise, and the comparison is against the stand's own first hour,
not against the load stand.

### 3. The saga under contention

Correctness before speed. Many concurrent purchases on the **same** account, and the same against the
declining server, at a rate above what the saga is comfortable with:

- the balance never goes below zero, and never below what the ledger says — read from the database
  after the run, not from the responses;
- no order is charged twice and no compensated order left money missing;
- the time from a refusal to its compensation, as a distribution;
- lock waits in Postgres during the run.

**Decides:** whether the saga's hold is a hold. This is the measurement most likely to find a defect
rather than a number, and it goes before the others for that reason.

### 4. Realtime fan-out

N subscribers on the SSE channel (a staircase: 10, 100, 1 000) while the simulator produces usage
events at a known rate. Measured at the subscriber: the delay from the event's timestamp (the server
stamps it) to its arrival, and **every update arrives exactly once and in order** — the property
[B-113](B-113-a-live-update-replaces-a-row-with-a-card.md) and
[B-107](B-107-a-smaller-segment-truncates-the-log-and-wedges-the-consumer.md) were about, now under load. The
k6 SSE support is limited; if it cannot hold a thousand streams honestly, the subscriber is a small
Kotlin program on the generator box, and the report says which.

**Decides:** how many open channels one server carries, and whether a dropped or duplicated update
is a thing that happens under load.

### 5. The cost of being observed

Profile 1 twice: with tracy and metrik configured as on the test contour, and with both pointed at
nothing (their agents answer a missing endpoint by doing nothing, which is exactly the switch). The
delta in p99 and in server CPU at the same rate.

**Decides:** the one number the README's subject asks for and does not yet have — what observing
the server costs the server.

### 6. Cold start and rollout

From container start to the readiness probe answering, and the latency of the first hundred
requests after it, on the load stand with the chart's limits. Repeated, because JIT is not
deterministic. Then the same read off the test contour's rollout of a tagged release.

**Decides:** whether the chart's probe timings are right, and what "the first request after a
deploy" costs a subscriber. The lesson behind it: a `CrashLoopBackOff` under a too-tight probe looks
like a slow rollout from outside.

### 7. The cost of the wire

No load needed. For every screen the server serves: the JSON size in bytes (gzip and plain), the
number of nodes, and on the client the time from the tree's arrival to the first frame, measured in
a test through the real `KonektScreenSource` at 393×852. A table per screen.

**Decides:** whether the dictionary's growth (B-114 added `surface`, `icon`, `screen_header`) has a
price a phone notices, and which screen is the heaviest.

### 8. The broker

booblik's throughput on the stand with the outbox publisher as the producer — events per second at
which the consumer's lag starts to grow — and the time to recover after the broker is restarted
under load: how long until the consumer is back at the high watermark, and whether any event was
lost or duplicated on the way (the same exactly-once property as 4, read from the consumer's log).

**Decides:** the number booblik's own README can cite for this workload, and whether the reconnect
from B-107 holds under load rather than only in a test.

## The order

1. **The stand script and the k6 scenarios**, run once against the compose stand on the build box
   to prove the pipeline end to end — the numbers from that run are thrown away, the box is shared.
2. **3 (contention)** on the rented load stand, first, because it can find a defect that changes
   everything after it.
3. **1 (RPS)**, then **5** on the same rented stand in the same session — the stand is the same, so
   the two are comparable.
4. **4** and **8** on the same session if the stand holds; they need the broker under load.
5. **6** on the stand and on the test contour.
6. **2 (the soak)** on the spare box, started last and left running; **7** alongside it on any
   machine, since it needs none.
7. The report: `docs/research/research-measurements.md`, one section per measurement, every figure
   with its caption; the README's cost paragraph updated with the two or three numbers that earned it.

## Progress

### 1 — the stand script and the scenarios, done; the pipeline proved on the build box

`scripts/measure/stand-up.sh` takes a box to the stand with `deploy/compose.measure.yaml` layered
on (the chart's limits on the server, the GC log, the declining server behind a profile), every
host port on loopback and overridable, the environment file and every output **outside the tree**
— on the build box the tree is a mutagen replica, and the first dry run lost its environment file
to the next sync ten seconds after writing it. `scripts/measure/k6.sh` runs a scenario as the
caller on the stand's own network; `sample.sh` is the soak's sampler; `contention-check.sh` reads
the ledger's invariants off the database (hold −, release +, top-up +, reversal −, capture and
decline 0, so the sum of an account's entries *is* its balance).

Four scenarios ran end to end on the build box — `screens`, `purchase`, `contention`, `soak` —
every check green and the invariants at zero. Those numbers are thrown away: the box is shared and
the point was the pipeline.

### 2 — the soak, running

Started **2026-09-02 14:26 UTC** on the spare box, image `ghcr.io/youndie/konekt-server:v0.1.40`
under the chart's limits, `SIMULATE_TRAFFIC` on, `DURATION=12h READ_RATE=3 BUY_PER_MINUTE=2
SUBSCRIBERS=20`, the sampler every 60 s; both under `systemd-run`. The box carries a light booblik
soak of its own at the same time (its broker holds ~660 MiB and a few percent of CPU), which the
report names as noise. The first sample after the setup burst: the server at 143 MiB of its 1 GiB,
155 descriptors, 11 Postgres connections.

### 3 — the saga under contention, done: no defect

Two rented boxes (2 vCPU / 3.8 GiB each, a private link), the stand on one and k6 on the other.
Three runs of 200 attempts on an account funded for 20: exactly 20 completed and 180 refused every
time, the ledger's invariants at zero. Under fifty concurrent attempts a purchase costs ~250 ms at
the median and ~450 ms at p95 — the queue on the account row. The first three runs said "no
captures" and the database said "the harness is wrong": `POST /purchases` answers 202 and the
scenario had accepted only 200 and 201. Figures and captions in
[research-measurements](../research/research-measurements.md).

### 1 and 7 — the two profiles' knees, the wire; and a finding

Reading: no knee to 400 rps, the knee at ~800 on the one core the chart allows, saturated at ~1 400
— the server's CPU, with Postgres at 60%. Buying: flat to 40 a second, the knee between 80 and 160,
and it is Postgres's. The wire: the heaviest screen under five kilobytes, under one gzipped. All
in [research-measurements](../research/research-measurements.md), every figure with its window.

**The finding:** the simulator ticks every subscriber every five seconds, the staircases had signed
in 57 000 of them, and by the end the consumer had fallen behind the broker's retention — the later
rounds' tails carry that weight, the report says which, and the remaining runs go on a reset stand
with the simulator off (`reset.sh`, `SIMULATE_TRAFFIC`). The harness itself was wrong twice on the
way — 202 read as an error, a minute for a setup of thousands — and both times the database or the
point's own log said so before a number went into the report.

## Acceptance criteria

- AC: `scripts/measure/stand-up.sh` takes a fresh box to a running stand with the chart's limits and
  the scenarios in place, and is the way every measurement here was run.
- AC: every figure in the report names the machine, the limits, the commit, the tag, the scenario
  and the number of runs, and carries its spread.
- AC: measurement 3 is done before 1, and its result — a defect or a clean bill — is stated in the
  report either way.
- AC: the soak runs at least six hours and the report shows the per-hour slope of each sampled
  quantity, including the ones that were flat.
- AC: the README's "what each toolkit costs" paragraph cites the report for every number it states.

## Anchors

| What | Where |
|---|---|
| The stand as it is | `deploy/compose.yaml`, `Makefile` (`stand-up`) |
| The limits the number is about | `charts/konekt/values.yaml` — `resources` |
| The simulator | `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficSimulator.kt`, `SIMULATE_TRAFFIC` in the compose |
| The saga | `feature/purchase-server-domain/`, [B-08](B-08-purchase-saga.md) |
| The realtime chain | `feature/realtime-shared-api/`, [B-107](B-107-a-smaller-segment-truncates-the-log-and-wedges-the-consumer.md), [B-113](B-113-a-live-update-replaces-a-row-with-a-card.md) |
| How the saga's writes were counted | [research-architecture §1](../research/research-architecture.md) |
| The scenarios and the script | `scripts/measure/` — to write |
| The report | `docs/research/research-measurements.md` — to write |
