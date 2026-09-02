#!/usr/bin/env bash
# THE ORACLE'S TABLE (`B-117`): metrik's per-route figures on the stand — count, p50, p95, max — for
# a window, as CSV. Read after a run, from the stand box, through the collector the test contour
# is observed through; k6's own timings are the second column of a report, not the first.
#
#     scripts/measure/routes.sh "$FROM" "$TO" > routes.csv     # ISO-8601 or epoch millis
#
# metrik sits behind a proxy login; on the stand there is no proxy, and the headers the proxy would
# add are supplied here — which is fine on a loopback port and would be the whole security problem
# on a public one, which is why the stand binds it to loopback.
set -euo pipefail
FROM=${1:-}
TO=${2:-}
METRIK=${METRIK:-http://127.0.0.1:8190}
SERVICE=${SERVICE:-1}
to_ms() { case "$1" in ''|*[!0-9]*) date -u -d "$1" +%s000 ;; *) echo "$1" ;; esac; }
Q=""
[ -n "$FROM" ] && Q="from=$(to_ms "$FROM")"
[ -n "$TO" ] && Q="$Q&to=$(to_ms "$TO")"
curl -sf -H "X-Auth-Request-User: measure" -H "X-Auth-Request-Email: measure@konekt.local" \
  "$METRIK/api/services/$SERVICE/routes?${Q#&}" \
  | python3 -c '
import sys, json
rows = json.load(sys.stdin)
rows = rows if isinstance(rows, list) else rows.get("routes", [])
print("method,route,status,count,p50_ms,p95_ms,max_ms")
for r in rows:
    print(",".join(str(r.get(k, "")) for k in ("method", "route", "status", "count", "p50Ms", "p95Ms", "maxMs")))
'
