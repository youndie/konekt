---
id: research-measurements
title: "konekt — what the stack costs, measured"
type: research
status: active
date: 2026-09-02
---

# What the stack costs, measured

The measurements [B-117](../backlog/B-117-what-the-stack-costs-measured-under-load-and-over-time.md)
asked for, one section each, every figure with what it was measured on. A figure without that
caption is not in this document. The method — warm-up excluded, three runs per point, absolute and
relative together, the axis decided before the run — is in the item and is not repeated here.

## The stands

| | The load stand | The soak stand |
|---|---|---|
| Machines | two rented boxes, Hetzner Helsinki, **2 vCPU / 3.8 GiB / 38 GB** each, Ubuntu 26.04.1: `hel1-1` runs the stand, `hel1-2` runs k6; a private link between them, RTT 0.5–2.2 ms (mean 0.9) | the owner's spare box, 2 cores / 5.8 GiB, Ubuntu 24.04, docker 29.1.3 — **shared with a light booblik soak** (its broker holds ~660 MiB, a few percent of CPU) |
| What runs | `deploy/compose.yaml` + `deploy/compose.measure.yaml`: the server under the chart's limits (`cpu: 1`, `memory: 1Gi`), Postgres 18, booblik 0.3.0, metrik, tracy, katcher; no declining server unless a scenario asks | the same, with `SIMULATE_TRAFFIC=true` |
| Image | `ghcr.io/youndie/konekt-server:v0.1.40` (commit `cded0a8`) | the same |
| Generator | k6 0.54.0 in its container on `hel1-2`, `--network host`, over the private link | k6 0.54.0 on the box, on the stand's network |
| Oracle | metrik on the stand (`scripts/measure/routes.sh`), k6's own timings beside it; `docker stats` every 10 s through `sample.sh` | `sample.sh` every 60 s: RSS, descriptors, connections, table sizes, GC |
| Scripts | `scripts/measure/`, commit `dc578f6` and after | the same |

Everything on the load stand ran on **2026-09-02**, one session, in the order the item gives.

## 3. The saga under contention — no defect

**What was measured.** One account funded for exactly 20 purchases of the $15 plan, asked for 200 at
once: k6 `shared-iterations`, 50 VUs, 200 iterations (`scripts/measure/k6/contention.js`,
`AFFORDABLE=20 ATTEMPTS=200 RATE=50`). Three runs, three fresh accounts. Afterwards
`contention-check.sh` read the ledger.

**What came out.**

| Run | Completed | Rejected | Requests | Failed | p50 | p95 | max |
|---|---|---|---|---|---|---|---|
| 1 | 20 | 180 | 224 | 0 | 270 ms | 591 ms | 856 ms |
| 2 | 20 | 180 | 224 | 0 | 234 ms | 390 ms | 526 ms |
| 3 | 20 | 180 | 224 | 0 | 245 ms | 453 ms | 688 ms |

Every run: exactly the 20 the money allowed completed, the other 180 refused as `insufficient_funds`
at the hold; **no account below zero, every balance equal to the sum of its ledger, no order held
or captured twice** — over all three accounts and over the 134 holds and 60 captures the session
had made by then. The saga's hold is a hold.

**What it costs.** Under fifty concurrent attempts on one account the purchase takes a quarter of a
second at the median and half a second at p95 — the queue on the account row, since a single
purchase on an idle stand is 4–10 ms (`min` in every run). That is the price of the invariant, and
it is paid only by the account under contention.

**A note on the harness.** The first three runs of this scenario reported *no* captures with the
same twenty holds, and the ledger agreed. The saga was fine: `POST /purchases` answers **202** and
the scenario's `buy()` accepted only 200 and 201, so it never confirmed. The harness's output is
data too, and the database was the thing that said which side was wrong.

## 1. Load by RPS — reading screens

**What was measured.** `GET /api/v1/screens/home`, `/plans` and `/plans/{planId}`, chosen at random
per request from 30 signed-in subscribers holding a plan, at a constant arrival rate held for 90 s
(`scripts/measure/k6/screens.js` through `staircase.sh`: one k6 invocation per rate, three rounds,
15 s between points). metrik's route table read for each point's window; the server's CPU from
`docker stats` every 10 s, as a share of its one allowed core. The first staircase was 25 → 400,
extended when nothing gave.

**What came out** — metrik's figures, the three rounds separated by slashes; k6's own p50/p95 across
the three routes beside them; failures were zero at every point.

| rps | route | p50 ms | p95 ms | k6 p50 / p95 (all routes) | server CPU mean–max % |
|---|---|---|---|---|---|
| 25 | home | 3.9 / 3.5 / 3.3 | 7.7 / 5.3 / 5.1 | 3.1–4.1 / 9.0–11.1 | 11–33 |
| 25 | plans, plan detail | 1.1 | 1.2–3.4 | | |
| 50 | home | 3.5 / 3.4 / 3.3 | 6.5 / 5.1 / 4.8 | 3.0–3.4 / 6.9–8.7 | 18–34 |
| 50 | plans, plan detail | 1.1 | 1.2–2.0 | | |
| 100 | home | 3.3 / 3.2 / 3.1 | 5.5 / 5.0 / 4.7 | 2.8–2.9 / 6.3–7.0 | 26–54 |
| 100 | plans, plan detail | 1.0–1.1 | 1.2–1.8 | | |
| 200 | home | 3.0 / 2.0 / 2.0 | 5.5 / 5.0 / 5.0 | 2.5 / 5.9–6.5 | 37–53 |
| 200 | plans, plan detail | 0.7–0.9 | 1.2 | | |
| 400 | home | 2.0 / 2.0 / 2.0 | 6.0 / 6.2 / 6.2 | 2.1–2.2 / 6.1–6.4 | 58–69 |
| 400 | plans, plan detail | 0.6–0.7 | 1.2–1.8 | | |

**What it says.** Up to 400 requests a second — sixteen times the rate the test contour has ever
seen — the server answers a screen in one to four milliseconds at the median and under seven at p95,
and its CPU is at 60% of the one core the chart allows. Latency *falls* as the rate rises, from 4 ms
at 25 rps to 2 ms at 400: the JIT and the connection pool are warmer, and the 25-rps point is the
one closest to idle. The home screen costs three times a plan screen — it is the one that reads
counters and the ledger — and the difference is the whole of the per-route spread.

k6's p50 sits a millisecond above metrik's, which is the private link and the generator's own
overhead, and k6's p95 above metrik's by three: the tail the collector does not see is the wire's.
Where the two disagree more than that, the report will say so; here they do not.

**The JVM runs the Serial collector.** `-Xlog:gc` on the stand opens with `Using Serial`: with one
CPU allowed, HotSpot's ergonomics pick the single-threaded collector and a 247 MB heap, and at
400 rps a young collection runs about once a second for 5–7 ms. That is the chart's `cpu: 1`
choosing the garbage collector, and it is the first thing to try when the knee is found.

## 7. The cost of the wire

**What was measured.** Every recorded screen the client's goldens are drawn from
(`client/src/jvmTest/resources/recorded/*.json` — the server's own responses at `v0.1.40`, captured
through the API): the JSON re-serialised without whitespace, the same gzipped at level 9, the
number of nodes in the tree, the number of distinct wire types, and the depth. No load, no stand.

| screen | bytes | gzip | nodes | types | depth |
|---|---|---|---|---|---|
| plan detail | 4,310 | 954 | 35 | 7 | 5 |
| home | 3,369 | 1,031 | 24 | 8 | 5 |
| home, eSIM not installed | 3,130 | 920 | 23 | 8 | 5 |
| orders | 2,981 | 904 | 13 | 7 | 3 |
| confirm purchase | 2,161 | 672 | 19 | 6 | 5 |
| profile | 1,992 | 690 | 14 | 6 | 4 |
| order (paid) | 1,974 | 577 | 16 | 6 | 4 |
| plans | 1,957 | 690 | 8 | 5 | 2 |
| eSIM, done | 1,709 | 746 | 12 | 9 | 3 |
| order (refused) | 1,561 | 489 | 13 | 6 | 4 |
| eSIM, scan | 1,336 | 577 | 9 | 7 | 3 |
| enter the code | 1,278 | 497 | 8 | 6 | 2 |
| eSIM, before you start | 920 | 475 | 7 | 6 | 3 |
| top up | 685 | 345 | 5 | 4 | 2 |
| sign in | 583 | 322 | 4 | 4 | 2 |

**What it says.** The heaviest screen the product serves is under five kilobytes plain and under one
gzipped; the median is two kilobytes. B-114 and B-115 added three types to the dictionary
(`surface`, `icon`, `screen_header`) and a card's worth of structure to the plan page — the
largest tree is 35 nodes, five deep — and none of it is a size a phone notices: one screen is
smaller than one icon in a native app. What the dictionary costs is a client release per type, not
bytes.

**Not measured here:** the time from a tree's arrival to the first frame on the client. It needs a
test through the real `KonektScreenSource` at 393×852 with the clock read at the frame, and that
harness is not written; the number is absent rather than estimated.

