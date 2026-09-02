#!/usr/bin/env bash
# THE ORACLE PER POINT (`B-117`): for every line of a `points.csv` the staircase driver wrote on the
# generator, ask metrik on the stand for the route table of that point's window, and write one
# CSV per point beside a joined `points-routes.csv` — scenario, round, rate, route, count, p50, p95.
#
#     scripts/measure/points-routes.sh /path/to/points.csv > points-routes.csv
#
# Run on the stand box, where metrik's loopback port is.
set -euo pipefail
cd "$(dirname "$0")/../.."
POINTS=${1:?usage: points-routes.sh <points.csv>}
echo "scenario,round,rate,method,route,status,count,p50_ms,p95_ms,max_ms"
tail -n +2 "$POINTS" | while IFS=, read -r scenario rnd rate hold frm to rest; do
  scripts/measure/routes.sh "$frm" "$to" | tail -n +2 | while IFS= read -r line; do
    echo "$scenario,$rnd,$rate,$line"
  done
done
