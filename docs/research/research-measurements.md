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
