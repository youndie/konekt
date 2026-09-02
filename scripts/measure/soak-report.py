#!/usr/bin/env python3
"""THE SOAK'S SLOPES (`B-117`, measurement 2): for every quantity the sampler recorded, the value at
the start and the end, the slope per hour by least squares, and — the number that decides — how
much of the window's change the slope explains. A flat line for six hours is a result; a slope is
a defect with its rate attached; a jump is neither and is shown as such.

    scripts/measure/soak-report.py samples.csv [skip-first-minutes]
"""
import csv
import sys
from datetime import datetime, timezone

path = sys.argv[1]
skip = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
rows = list(csv.DictReader(open(path)))


def ts(r):
    return datetime.strptime(r["ts"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()


t0 = ts(rows[0])
rows = [r for r in rows if ts(r) - t0 >= skip * 60]
hours = (ts(rows[-1]) - ts(rows[0])) / 3600
print(f"window: {rows[0]['ts']} .. {rows[-1]['ts']} ({hours:.1f} h after skipping the first {skip:.0f} min), {len(rows)} samples")
print("| quantity | first | last | slope per hour | note |")
print("|---|---|---|---|---|")
for col in [c for c in rows[0].keys() if c != "ts"]:
    pts = [(ts(r) / 3600, float(r[col])) for r in rows if r[col] not in ("", None)]
    if len(pts) < 3:
        continue
    n = len(pts)
    mx = sum(p[0] for p in pts) / n
    my = sum(p[1] for p in pts) / n
    sxx = sum((p[0] - mx) ** 2 for p in pts)
    slope = sum((p[0] - mx) * (p[1] - my) for p in pts) / sxx if sxx else 0.0
    first, last = pts[0][1], pts[-1][1]
    lo, hi = min(p[1] for p in pts), max(p[1] for p in pts)
    note = "flat" if abs(slope * hours) < max(1.0, 0.02 * max(abs(first), 1)) else ("grows" if slope > 0 else "falls")
    if hi - lo > 5 * max(1.0, abs(slope * hours)):
        note += ", spiky"
    print(f"| {col} | {first:g} | {last:g} | {slope:+.3g} | {note} |")
