#!/usr/bin/env bash
# THE STAIRCASE AS SEPARATE RUNS (`B-117`, measurement 1): one k6 invocation per rate per round,
# so every point has its own window — and the window is what metrik on the stand is asked about
# afterwards (`routes.sh FROM TO`), which a single staircase inside one k6 run would blur into one
# table. Each point appends a line to `points.csv`: the scenario, the round, the rate, the window,
# and k6's own p50 / p95 / failure share as the second column of the report.
#
#     scripts/measure/staircase.sh screens 25,50,100,200,400 90 3
#     scripts/measure/staircase.sh purchase 5,10,20,40 60 3
#
# Runs for as long as rates × hold × rounds says; start it under `systemd-run --unit=…` and read
# `points.csv` as it grows.
set -euo pipefail
cd "$(dirname "$0")/../.."

SCENARIO=${1:?usage: staircase.sh <scenario> <rates,comma,separated> <hold seconds> [rounds]}
RATES=${2:?rates}
HOLD=${3:?hold}
ROUNDS=${4:-3}
OUT=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}/out
mkdir -p "$OUT"
POINTS="$OUT/points.csv"
[ -f "$POINTS" ] || echo "scenario,round,rate,hold_s,from,to,k6_reqs,k6_p50_ms,k6_p95_ms,k6_failed_share,k6_completed,k6_rejected" > "$POINTS"

for round in $(seq 1 "$ROUNDS"); do
  for rate in ${RATES//,/ }; do
    from=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    # Any extra `NAME=value` for the scenario comes through `EXTRA` as one string; an empty array
    # expansion used to hand k6 an empty argument and every point closed in a second.
    # shellcheck disable=SC2086
    if ! scripts/measure/k6.sh "$SCENARIO" "RATES=$rate" "HOLD=$HOLD" ${EXTRA:-} > "$OUT/$SCENARIO-point.log" 2>&1; then
      echo "$SCENARIO round $round rate $rate: k6 failed — $(grep -m1 -iE "error|fail" "$OUT/$SCENARIO-point.log")"
      exit 1
    fi
    log=$(ls -t "$OUT/$SCENARIO"-*.json | head -1)
    to=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    python3 - "$log" "$SCENARIO" "$round" "$rate" "$HOLD" "$from" "$to" >> "$POINTS" <<'PY'
import json, sys
summary, scenario, rnd, rate, hold, frm, to = sys.argv[1:8]
m = json.load(open(summary))["metrics"]
def v(name, key):
    return m.get(name, {}).get(key, "")
print(",".join(str(x) for x in [scenario, rnd, rate, hold, frm, to, v("http_reqs", "count"), round(v("http_req_duration", "med") or 0, 2),
      round(v("http_req_duration", "p(95)") or 0, 2), round(v("http_req_failed", "value") or 0, 4), v("outcome_completed", "count"), v("outcome_rejected", "count")]))
PY
    echo "$SCENARIO round $round rate $rate: $from .. $to"
    sleep 15
  done
done
