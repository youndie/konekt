#!/usr/bin/env bash
# COLD START (`B-117`, measurement 6): restart the server container N times and, each time, measure
# the time from `docker start` to `/health` answering, and the latency of the first hundred requests
# after it — the ones a subscriber makes into a JVM that has not warmed up. Run on the stand box,
# against the stand's own loopback port. One CSV line per restart plus the hundred latencies.
#
#     scripts/measure/coldstart.sh 5 http://127.0.0.1:8080
set -euo pipefail
RUNS=${1:-5}
BASE=${2:-http://127.0.0.1:8080}
PROJECT=${PROJECT:-konekt}
OUT=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}/out
mkdir -p "$OUT"
CSV="$OUT/coldstart.csv"
echo "run,start_to_healthy_ms,first_ms,p50_first100_ms,p95_first100_ms,max_first100_ms" > "$CSV"

now_ms() { date +%s%3N; }

# A signed-in subscriber, so the hundred requests are the home screen and not a 401: the dev OTP
# route hands the code back, the same door the scenarios use.
MSISDN="+1555$((1000000 + RANDOM * 9 % 9000000))"
curl -sf -o /dev/null -X POST -H 'Content-Type: application/json' -d "{\"msisdn\":\"$MSISDN\"}" "$BASE/api/v1/auth/otp/request"
CODE=$(curl -sf "$BASE/api/v1/dev/otp?msisdn=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$MSISDN")" | python3 -c 'import sys,json; print(json.load(sys.stdin)["code"])')
TOKEN=$(curl -sf -X POST -H 'Content-Type: application/json' -d "{\"msisdn\":\"$MSISDN\",\"code\":\"$CODE\"}" "$BASE/api/v1/auth/otp/verify" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
for run in $(seq 1 "$RUNS"); do
  docker stop -t 20 "${PROJECT}-server-1" >/dev/null
  t0=$(now_ms)
  docker start "${PROJECT}-server-1" >/dev/null
  until curl -sf -o /dev/null "$BASE/health"; do sleep 0.1; done
  healthy=$(( $(now_ms) - t0 ))
  lat=$(for i in $(seq 1 100); do curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" -w '%{time_total}\n' "$BASE/api/v1/screens/home"; done | awk '{printf "%.1f\n", $1*1000}')
  first=$(echo "$lat" | head -1)
  sorted=$(echo "$lat" | sort -n)
  p50=$(echo "$sorted" | sed -n 50p); p95=$(echo "$sorted" | sed -n 95p); max=$(echo "$sorted" | tail -1)
  echo "$run,$healthy,$first,$p50,$p95,$max" | tee -a "$CSV"
  sleep 5
done
