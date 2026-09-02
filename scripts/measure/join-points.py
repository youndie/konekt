#!/usr/bin/env python3
"""THE TABLE A REPORT CITES (`B-117`): every point of a staircase joined with what the collector
saw in its window and what the sampler saw on the box — metrik's p50/p95 per route, k6's own p50/p95
beside them, the server's and Postgres's CPU (mean and max) and the server's memory.

    scripts/measure/join-points.py points.csv points-routes.csv samples.csv [route-prefix]

Prints one line per point and writes `<points>-joined.csv` beside the input.
"""
import csv
import sys
from collections import defaultdict

points_path, routes_path, samples_path = sys.argv[1:4]
prefix = sys.argv[4] if len(sys.argv) > 4 else "/api/v1/"
points = list(csv.DictReader(open(points_path)))
routes = list(csv.DictReader(open(routes_path)))
samples = list(csv.DictReader(open(samples_path)))


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def stats(values):
    values = [v for v in values if v is not None]
    return (sum(values) / len(values), max(values)) if values else (None, None)


out_path = points_path.replace(".csv", "-joined.csv")
with open(out_path, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["scenario", "round", "rate", "from", "to", "k6_reqs", "k6_p50_ms", "k6_p95_ms", "k6_failed_share",
                "server_cpu_mean", "server_cpu_max", "postgres_cpu_mean", "postgres_cpu_max", "server_mem_max_mib",
                "route", "metrik_count", "metrik_p50_ms", "metrik_p95_ms", "metrik_max_ms"])
    for p in points:
        window = [s for s in samples if p["from"] <= s["ts"] <= p["to"]]
        scpu = stats([num(s.get("server_cpu_pct")) for s in window])
        pcpu = stats([num(s.get("postgres_cpu_pct")) for s in window])
        smem = stats([num(s.get("server_mem_mib")) for s in window])
        rows = [r for r in routes if r["round"] == p["round"] and r["rate"] == p["rate"] and r["route"].startswith(prefix)]
        head = (f"r{p['round']} {p['rate']:>5} rps  k6 p50={p['k6_p50_ms']} p95={p['k6_p95_ms']} fail={p['k6_failed_share']}  "
                f"server cpu {scpu[0] and round(scpu[0])}/{scpu[1] and round(scpu[1])}%  pg cpu {pcpu[0] and round(pcpu[0])}/{pcpu[1] and round(pcpu[1])}%  "
                f"mem {smem[1] and round(smem[1])}MiB")
        print(head)
        for r in rows:
            print(f"      {r['route']:<40} n={r['count']:>6} p50={float(r['p50_ms']):6.1f} p95={float(r['p95_ms']):6.1f} max={r['max_ms']}")
            w.writerow([p["scenario"], p["round"], p["rate"], p["from"], p["to"], p["k6_reqs"], p["k6_p50_ms"], p["k6_p95_ms"], p["k6_failed_share"],
                        scpu[0], scpu[1], pcpu[0], pcpu[1], smem[1], r["route"], r["count"], r["p50_ms"], r["p95_ms"], r["max_ms"]])
print(f"written {out_path}")

# THE ROUNDS SIDE BY SIDE: one line per rate and route, the three rounds' figures separated by
# slashes, so the spread is visible and a single lucky round cannot pose as the result.
by = defaultdict(list)
for r in csv.DictReader(open(out_path)):
    by[(int(r["rate"]), r["route"])].append(r)
print()
print("rate | route | metrik p50 by round | metrik p95 by round | k6 p50 | k6 p95 | server cpu mean-max | pg cpu mean-max")
for (rate, route), rs in sorted(by.items()):
    rs.sort(key=lambda r: int(r["round"]))
    j = lambda key, fmt="{:.1f}": "/".join(fmt.format(float(r[key])) if r[key] not in ("", "None") else "-" for r in rs)
    cpu = "/".join(f"{float(r['server_cpu_mean']):.0f}-{float(r['server_cpu_max']):.0f}" if r["server_cpu_mean"] not in ("", "None") else "-" for r in rs)
    pg = "/".join(f"{float(r['postgres_cpu_mean']):.0f}-{float(r['postgres_cpu_max']):.0f}" if r["postgres_cpu_mean"] not in ("", "None") else "-" for r in rs)
    print(f"{rate:>5} | {route:<32} | {j('metrik_p50_ms'):<16} | {j('metrik_p95_ms'):<16} | {j('k6_p50_ms')} | {j('k6_p95_ms')} | {cpu} | {pg}")
