#!/usr/bin/env bash
# THE BROKER AND ITS CONSUMER (`B-117`, measurement 8), read off the database: with the simulator on,
# every subscriber holding counters is sent 25 MB of data usage every five seconds, so the sum of
# consumed data units across the stand should grow by 25 × N per tick. This samples that sum every
# five seconds and prints the applied rate beside the produced rate; when applied falls behind, the
# consumer is behind the broker, and by how much. No dev endpoint, no broker CLI — the ledger of
# usage is the counters themselves.
#
#     scripts/measure/broker-lag.sh 120          # seconds; run on the stand box
set -uo pipefail
SECONDS_TO_RUN=${1:-120}
PROJECT=${PROJECT:-konekt}
q() { docker exec "${PROJECT}-postgres-1" psql -U konekt -d konekt -Atc "$1"; }
echo "ts,subscribers_with_data,consumed_mb_total,applied_mb_per_5s,produced_mb_per_5s,lag_ticks"
prev=""
end=$(( $(date +%s) + SECONDS_TO_RUN ))
while [ "$(date +%s)" -lt "$end" ]; do
  row=$(q "select count(*), coalesce(sum(limit_units - remaining_units),0) from usage_counter where kind = 'data'")
  n=${row%%|*}; total=${row##*|}
  if [ -n "$prev" ]; then
    applied=$(( total - prev )); produced=$(( 25 * n ))
    lag=$(python3 -c "print(round(($produced - $applied) / max(1, $produced), 2))")
    echo "$(date -u +%FT%TZ),$n,$total,$applied,$produced,$lag"
  fi
  prev=$total
  sleep 5
done
