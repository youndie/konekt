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

The staircase was then extended to 800 and 1 600 with the sampler also reading Postgres's CPU:

| rps | route | p50 ms | p95 ms | k6 p50 / p95 (all routes) | server CPU mean–max % | Postgres CPU % |
|---|---|---|---|---|---|---|
| 800 | home | 3.6 / 4.2 / 4.7 | 53 / 68 / 82 | 2.8–3.5 / 31–47 | 87–96 | 48–59 |
| 800 | plans, plan detail | 0.9–1.1 | 14–23 | | | |
| ≈1 390 (asked 1 600) | home | 74 / 77 / 77 | 194 / 191 / 195 | 101–107 / 234–239 | 82–101 | 56–64 |
| ≈1 390 | plans, plan detail | 12–14 | 71–75 | | | |

At 1 600 the generator delivered 125 000 of the 144 000 requests it was asked for — about 1 390 a
second — because the server stopped taking them faster; every one that arrived was answered, and
none failed.

**What it says.** Up to 400 requests a second — sixteen times the rate the test contour has ever
seen — the server answers a screen in one to four milliseconds at the median and under seven at p95,
and its CPU is at 60% of the one core the chart allows. **The knee is at about 800 a second on that
core**: the server's CPU reaches 90% and the p95 of the home screen goes from 6 ms to 50–80 while
its median barely moves — the queue forms, the work does not change. Past it, at the ~1 400 the
generator could push through, the core is at 100% and the median is 75 ms. What saturated is the
server: Postgres sat at 55–60% of its own core at the same moments, and the broker under 1%. Latency *falls* as the rate rises, from 4 ms
at 25 rps to 2 ms at 400: the JIT and the connection pool are warmer, and the 25-rps point is the
one closest to idle. The home screen costs three times a plan screen — it is the one that reads
counters and the ledger — and the difference is the whole of the per-route spread.

k6's p50 sits a millisecond above metrik's up to 400 rps, which is the private link and the
generator's own overhead, and k6's p95 above metrik's by three: the tail the collector does not see
is the wire's. Past the knee the two part company the other way: at ~1 400 rps metrik's median is
75 ms and k6's is 102 — the 27 ms between them is time a request spent in the accept queue before
the server stamped it, which the collector cannot see by construction. That is the reason for two
columns: the collector measures the server, the generator measures the subscriber.

**The JVM runs the Serial collector.** `-Xlog:gc` on the stand opens with `Using Serial`: with one
CPU allowed, HotSpot's ergonomics pick the single-threaded collector and a 247 MB heap, and at
400 rps a young collection runs about once a second for 5–7 ms. That is the chart's `cpu: 1`
choosing the garbage collector, and it is the first thing to try when the knee is found.

## 1. Load by RPS — buying

**What was measured.** The saga end to end — `POST /api/v1/purchases` then `POST …/confirm`, the
hold, the provision, the capture, the outbox, booblik and the consumer — at a constant arrival rate
of *purchases* held for 60 s, each purchase a funded subscriber's first (`purchase.js` through
`staircase.sh`; every point signs its own subscribers in beforehand, which is why a 40-a-second
point starts with 2 450 sign-ins and why k6's default minute for setup had to go). Three rounds.

**What came out** — metrik per route, rounds separated by slashes; k6's p50/p95 across both
requests; every purchase in every round completed, none was refused, no request failed.

| purchases/s | route | p50 ms | p95 ms | k6 p50 / p95 | server CPU mean–max % | Postgres CPU % |
|---|---|---|---|---|---|---|
| 5 | purchases | 10.5 / 10.4 / 10.7 | 16.0 / 17.4 / 17.1 | 8.4–8.9 / 18.8–18.9 | 31–89 | 43–61 |
| 5 | confirm | 14.0 / 14.2 / 14.6 | 21.9 / 21.3 / 22.1 | | | |
| 10 | purchases | 10.6 / 10.3 / 10.1 | 16.8 / 17.4 / 15.2 | 8.6–8.7 / 18.1–18.3 | 32–54 | 43–58 |
| 10 | confirm | 14.0 / 13.6 / 13.8 | 21.2 / 21.5 / 21.2 | | | |
| 20 | purchases | 10.2 / 9.6 / 9.7 | 17.1 / 15.1 / 15.1 | 8.7 / 17.2–18.0 | 42–55 | 50–61 |
| 20 | confirm | 13.8 / 13.2 / 13.2 | 21.0 / 20.4 / 20.6 | | | |
| 40 | purchases | 9.0 / 9.4 / 9.4 | 17.4 / 19.3 / 19.5 | 8.5–8.6 / 17.7–18.6 | 48–80 | 56–63 |
| 40 | confirm | 12.6 / 12.8 / 13.1 | 21.3 / 23.1 / 23.7 | | | |

Extended to 80 and 160 a second (three rounds, the sampler reading Postgres too):

| purchases/s | route | p50 ms | p95 ms | k6 p50 / p95 | delivered | server CPU % | Postgres CPU % |
|---|---|---|---|---|---|---|---|
| 80 | purchases | 10.9 / 11.2 / 11.7 | 40 / 49 / 85 | 8.9–9.0 / 27–40 | 4 800 of 4 800 | 49–92 | 57–112 |
| 80 | confirm | 15.2 / 15.3 / 15.8 | 46 / 55 / 100 | | | | |
| 160 | purchases | 187 / 176 / 161 | 390 / 356 / 339 | 8.9–9.4 / 339–376 | ≈9 000–9 260 of 9 600 | 55–85 | 56–100 |
| 160 | confirm | 210 / 202 / 185 | 416 / 389 / 374 | | | | |

**What it says.** A purchase costs the subscriber about 9 ms to start and 13 ms to confirm at the
median, 20 ms at p95, and the figure does not move between five and forty purchases a second. **The
knee is between 80 and 160 a second**: at 80 the medians hold and the tail lengthens (p95 40 → 85 ms
across the rounds — the simulator's weight, see the finding below), at 160 the median itself is
160–190 ms, the generator delivers 9 000 of the 9 600 it was asked for, and Postgres touches 100%
of a core. This knee is Postgres's, not the server's — the other way round from the reading
profile, and the reason the sampler records both. The saga is the
expensive request on this server: forty of them a second put the server's core at 50–60% and
Postgres at 60%, where four hundred screen reads had cost the same — one purchase is roughly ten
screens, which is what ≈17 writes, an outbox row and a broker publish come to.

The confirm is 4 ms dearer than the start at every rate: that is the capture, the outbox and the
provision, against the hold alone.

**Where the two columns disagree.** Here metrik's medians sit *above* k6's, by one to five
milliseconds, where on the reading profile they sat below. metrik's percentiles come from
exponential histogram buckets and carry up to 20% of bucket width (its own README says so); at
10 ms that is the whole of the gap. Neither column is wrong; the table keeps both and the report
does not add them.

## A finding beside the staircases: the simulator scales with the subscribers

**What was seen.** After the two staircases the load stand held **57 463 subscribers and 88 665
usage counters** — every point signs its own subscribers in, and a 160-a-second purchase point
signs in 9 650. `TrafficSimulator` ticks every five seconds and, on each tick, sends three usage
events for *every* subscriber holding counters: at the end of the session that is ~170 000 events
per tick through booblik, the consumer and Postgres, on top of whatever k6 was sending. The server
log confirms the consumer fell behind the broker's 128 MiB retention and skipped ahead — *"the
broker no longer has offset 15643314 on partition 0 — retention passed this consumer, so 1 735 752
usage events were skipped"* — which is the recovery [B-107](../backlog/B-107-a-smaller-segment-truncates-the-log-and-wedges-the-consumer.md)
built, doing what it was built for, on a stand that never meant to ask for it.

**What it does to the figures above.** The reading profile's p95 at 800 rps drifts across the
rounds — 53, 68, 82 ms — and the buying profile's server CPU at 5 purchases a second is 30–90%
where it should be near idle: the background grew with every point. The medians did not move, so
the knees stand; the tails and the CPU columns of later rounds carry the simulator's weight, and
this document says so rather than averaging it away.

**A rerun that was not what it said.** The stand was reset and the key points run again with the
simulator meant to be off — and it was not: the switch had been written into the stand script
after this stand was brought up, the environment file had no line for `sed` to change, and the
container still said `SIMULATE_TRAFFIC=true`. The reading points of that rerun stand (40
subscribers, a simulator's worth of nothing): 400 rps at p50 2.4 / p95 7.3 ms once warm — the first
point after the reset, p50 22 / p95 442, is the cold JVM and is excluded as warm-up. The buying
points of that rerun signed in 33 000 subscribers and are the same picture as before, with the
same weight. The stand was reset a second time with the variable **verified inside the container**
before the measurements that follow; the numbers above are not restated.

**What it says about the product.** The simulator is a mock, but its cost is the shape a real usage
stream would have: three events per subscriber per five seconds is 36 events a minute per line,
and the consumer that applies them is the same code either way. Fifty thousand lines put it past
the broker's retention on one core. That is a number for the reference-scope: what a real MVNO's
usage feed would ask of this consumer, and the point at which a single consumer stops being enough.

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

