#!/usr/bin/env bash
# RUN ONE SCENARIO on the stand's own network, so the generator reaches `server:8080` by name and no
# host port is involved (`B-117`).
#
#     scripts/measure/k6.sh screens RATES=10,25,50,100 HOLD=120
#     scripts/measure/k6.sh soak DURATION=6h
#
# Every `NAME=value` after the scenario is an environment variable the scenario reads; every
# scenario prints the ones it used at the top of its output, so a result cannot be read without
# its parameters. The summary goes to `measure-out/<scenario>-<timestamp>.json` beside the raw
# k6 output, which is the record a report cites — under `$MEASURE_HOME/out`, outside the tree.
set -euo pipefail
cd "$(dirname "$0")/../.."

SCENARIO=${1:?usage: k6.sh <scenario> [NAME=value ...]}
shift
PROJECT=${PROJECT:-konekt}
NETWORK=${NETWORK:-${PROJECT}_default}
# Outside the tree, for the reason `stand-up.sh` gives: on the build box the tree is a replica.
OUT=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}/out
mkdir -p "$OUT"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)

ENV_ARGS=()
for kv in "$@"; do ENV_ARGS+=(-e "$kv"); done

# The k6 image runs as its own user; a checkout synced with tight modes hides the scripts from it.
chmod -R a+rX scripts/measure/k6 2>/dev/null || true
# As the invoking user, so the summary it writes into `$OUT` is not owned by the image's own user
# and not refused by the directory the invoking user made.
docker run --rm --network "$NETWORK" --user "$(id -u):$(id -g)" \
  -v "$PWD/scripts/measure/k6:/scripts:ro" -v "$OUT:/out" \
  -e BASE="${BASE:-http://server:8080}" "${ENV_ARGS[@]}" \
  grafana/k6:0.54.0 run --summary-export "/out/$SCENARIO-$STAMP.json" "/scripts/$SCENARIO.js" \
  2>&1 | tee "$OUT/$SCENARIO-$STAMP.log"
