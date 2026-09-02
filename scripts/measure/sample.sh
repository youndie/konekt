#!/usr/bin/env bash
# THE SOAK'S SAMPLER (`B-117`, measurement 2): once a minute, what a green build never shows —
# the server's memory and CPU, its file descriptors, Postgres's connections, the tables that only
# ever grow, the broker's disk, and the JVM's last GC pause. One CSV line per sample.
#
#     systemd-run --unit=konekt-sample --collect $PWD/scripts/measure/sample.sh $HOME/.local/state/konekt-measure/out 60
#
# systemd rather than `nohup … &` from an ssh session: a process started from a session dies with
# it, and a sampler that stopped at logout looks like a soak that never grew.
set -uo pipefail
cd "$(dirname "$0")/../.."

DIR=${1:?usage: sample.sh <output dir> [interval seconds]}
INTERVAL=${2:-60}
PROJECT=${PROJECT:-konekt}
mkdir -p "$DIR"
CSV="$DIR/samples.csv"
[ -f "$CSV" ] || echo "ts,server_cpu_pct,server_mem_mib,server_fds,postgres_mem_mib,postgres_connections,broker_mem_mib,broker_disk_mib,outbox_rows,ledger_rows,usage_rows,gc_last_pause_ms,gc_lines" > "$CSV"

stat_of() { docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' | awk -v n="$1" '$1==n {gsub("%","",$2); split($3,m,"MiB"); if (m[1] ~ /GiB/) {gsub("GiB","",m[1]); m[1]=m[1]*1024}; print $2","m[1]}'; }
psql_q() { docker exec "${PROJECT}-postgres-1" psql -U konekt -d konekt -Atc "$1" 2>/dev/null || echo ""; }

while true; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  server=$(stat_of "${PROJECT}-server-1"); server=${server:-","}
  fds=$(docker exec "${PROJECT}-server-1" bash -c 'ls /proc/1/fd | wc -l' 2>/dev/null || echo "")
  pg=$(stat_of "${PROJECT}-postgres-1" | cut -d, -f2)
  conns=$(psql_q "select count(*) from pg_stat_activity where datname='konekt'")
  broker=$(stat_of "${PROJECT}-broker-1" | cut -d, -f2)
  bdisk=$(docker exec "${PROJECT}-broker-1" sh -c 'du -sm /var/lib/booblik 2>/dev/null | cut -f1' 2>/dev/null || echo "")
  outbox=$(psql_q "select count(*) from outbox_events")
  ledger=$(psql_q "select count(*) from ledger_entry")
  usage=$(psql_q "select count(*) from usage_counter")
  gc=$(docker exec "${PROJECT}-server-1" bash -c 'grep -o "Pause [A-Za-z ()]*[0-9.]*ms" /tmp/gc.log 2>/dev/null | tail -1 | grep -o "[0-9.]*ms" | tr -d ms; grep -c Pause /tmp/gc.log 2>/dev/null' 2>/dev/null | tr '\n' ',' | sed 's/,$//')
  echo "$ts,$server,$fds,$pg,$conns,$broker,$bdisk,$outbox,$ledger,$usage,$gc" >> "$CSV"
  sleep "$INTERVAL"
done
