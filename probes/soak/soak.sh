#!/usr/bin/env bash
# B-77'S SOAK, INSTRUMENTED — because the failure message measures the moment it breaks and nothing
# before it.
#
# The item's plan is to wait for a reproduction and read the diagnosis it now carries. That gives ONE
# reading, at the end, and two questions it cannot answer: whether the dead-tuple count grew smoothly
# or jumped, and — if the soak stays green — anything at all beyond "twelve hours was not enough this
# time". A negative result says nothing past its own length, which is the mistake this item was
# closed on once already.
#
# So this samples the same numbers on a timer and, less often, runs the two scenarios that fail. The
# reproduction then arrives with a curve behind it and a time-to-failure on it.
#
# THE COLUMN THAT MIGHT SETTLE IT WITHOUT A FAILURE AT ALL is `last_autovacuum`. The suspicion is
# unbounded dead row versions on `usage_counter`; if autovacuum is running on it the count plateaus
# and the story is wrong, and if it has never run the count grows without bound. That is readable an
# hour in rather than twelve.
#
#   probes/soak/soak.sh                    # load to 90 subscribers, then sample
#   SUBSCRIBERS=40 SAMPLE=300 probes/soak/soak.sh
#   PROBE=0 probes/soak/soak.sh            # sample only, never run the scenarios
set -u

H=${H:-http://127.0.0.1:8080}
COMPOSE=${COMPOSE:-deploy/compose.yaml}
SUBSCRIBERS=${SUBSCRIBERS:-90}
SAMPLE=${SAMPLE:-600}          # seconds between readings
PROBE=${PROBE:-1800}           # seconds between scenario runs; 0 disables them
# OUTSIDE THE REPLICA BY DEFAULT, and this is not a preference. mutagen syncs this repository one way
# and DELETES on the far side whatever the near side does not have; its ignore list is
# build/.gradle/.kotlin/.idea/.DS_Store and does NOT read .gitignore. A readings file written under
# `probes/` is erased within seconds — the first launch of this soak wrote into exactly such a file
# and would have produced twelve hours of nothing.
OUT=${OUT:-$HOME/soak-b77/soak.tsv}
mkdir -p "$(dirname "$OUT")"

# APPEND-ONLY, and written from the first line. A soak that is killed at hour nine must leave nine
# hours of readings behind; a file assembled at the end leaves nothing.
psql() { docker compose -f "$COMPOSE" exec -T postgres psql -U konekt -d konekt -tAc "$1" 2>/dev/null | tr -d ' \r'; }

subscriber() {
  M="1555$RANDOM$RANDOM"
  curl -s -X POST "$H/api/v1/auth/otp/request" -H 'Content-Type: application/json' -d "{\"msisdn\":\"$M\"}" >/dev/null
  C=$(curl -s "$H/api/v1/dev/otp?msisdn=$M" | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])" 2>/dev/null) || return 1
  T=$(curl -s -X POST "$H/api/v1/auth/otp/verify" -H 'Content-Type: application/json' -d "{\"msisdn\":\"$M\",\"code\":\"$C\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])" 2>/dev/null) || return 1
  curl -s -X POST "$H/api/v1/top-ups" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"amountMinor":5000}' >/dev/null
  O=$(curl -s -X POST "$H/api/v1/purchases" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"planId":"home-20gb-30d"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['orderId'])" 2>/dev/null) || return 1
  curl -s -X POST "$H/api/v1/purchases/$O/confirm" -H "Authorization: Bearer $T" >/dev/null
}

loaded() { psql "SELECT count(DISTINCT subscriber_id) FROM usage_counter"; }

echo "soak: loading to $SUBSCRIBERS subscribers (now $(loaded))"
while [ "$(loaded)" -lt "$SUBSCRIBERS" ]; do subscriber || sleep 2; done
echo "soak: loaded, $(loaded) subscribers. Writing to $OUT"

# A RESTART IS MARKED, NOT SMOOTHED OVER. `elapsed` counts from THIS process, so a soak that is
# interrupted and started again writes a second run into the same file with its own clock — and a
# reader adding the two would report a twelve-hour soak that was two sixes. This item has already had
# one measurement invalidated by a host that restarted underneath it; the marker is so the next
# reader sees the seam.
#
# `wall` and `host_uptime_s` are what make a gap visible without any marker at all: wall jumps by the
# length of the outage, and host uptime goes BACKWARDS if the machine rebooted rather than merely
# slept. Those two columns distinguish "the box was asleep" from "the box came back new", which is
# the difference between a soak that can be continued and one that has to start over.
if [ -s "$OUT" ]; then
  printf '# restart\t%s\thost_uptime=%s\n' "$(date -Iseconds)" "$(cut -d. -f1 /proc/uptime 2>/dev/null || echo -1)" >> "$OUT"
else
  printf 'elapsed_s\twall\thost_uptime_s\tpg_up\tbroker_up\tsubscribers\tlive\tdead\tsize\tautovac_count\tlast_autovac\tscenarios\n' >> "$OUT"
fi

START=$(date +%s)
LAST_PROBE=0
while true; do
  NOW=$(date +%s); ELAPSED=$(( NOW - START ))

  HOST_UP=$(cut -d. -f1 /proc/uptime 2>/dev/null || echo -1)
  PG_UP=$(docker compose -f "$COMPOSE" ps --format '{{.Service}}|{{.Status}}' | awk -F'|' '$1=="postgres"{print $2}' | tr ' ' '_')
  BR_UP=$(docker compose -f "$COMPOSE" ps --format '{{.Service}}|{{.Status}}' | awk -F'|' '$1=="broker"{print $2}' | tr ' ' '_')

  # THE FOUR NUMBERS THE ITEM ASKS FOR, plus the two that can refute the story early. `relname` and
  # not a regclass cast: this runs while the table is being updated three times a second and a lock
  # taken by a probe would be the probe changing what it measures.
  # SPLIT ON `|` AND NOT ON WHITESPACE. `pg_size_pretty` answers "672 kB", with a space in it, so a
  # whitespace split slides every later column one to the left and writes a plausible-looking row of
  # nonsense — which this probe did on its first run, for exactly as long as it took to look at.
  IFS='|' read -r LIVE DEAD SIZE AV_N AV_AT <<<"$(psql "SELECT n_live_tup||'|'||n_dead_tup||'|'||pg_size_pretty(pg_total_relation_size(relid))||'|'||autovacuum_count||'|'||coalesce(to_char(last_autovacuum,'HH24:MI:SS'),'never') FROM pg_stat_user_tables WHERE relname='usage_counter'" | tr -d '\n')"
  SUBS=$(loaded)

  SCEN="-"
  if [ "$PROBE" -gt 0 ] && [ $(( NOW - LAST_PROBE )) -ge "$PROBE" ]; then
    LAST_PROBE=$NOW
    # THE TWO THAT FAIL, and only those: the whole suite would take longer than the sampling interval
    # and would create subscribers of its own on every pass.
    rm -f e2e/build/test-results/e2e/*.xml 2>/dev/null
    if ./gradlew :e2e:e2e --tests '*LiveUpdateScenarioTest*' --tests '*RoamingScenarioTest*' -q >/dev/null 2>&1; then
      SCEN="pass"
    else
      # A NON-ZERO GRADLE IS NOT YET A REPRODUCTION. The stand going down, the box rebooting, a
      # compile error — all of them exit non-zero and none of them is what this soak is hunting, and
      # B-77 has already had one measurement invalidated by a host that restarted underneath it. So
      # the verdict comes from the test results: assertions that failed mean the scenarios failed,
      # and no results at all mean the harness did.
      RAN=$(grep -ho 'tests="[0-9]*"' e2e/build/test-results/e2e/*.xml 2>/dev/null | grep -o '[0-9]*' | paste -sd+ - | bc 2>/dev/null)
      BAD=$(grep -ho 'failures="[0-9]*"' e2e/build/test-results/e2e/*.xml 2>/dev/null | grep -o '[0-9]*' | paste -sd+ - | bc 2>/dev/null)
      if [ "${RAN:-0}" -gt 0 ] && [ "${BAD:-0}" -gt 0 ]; then
        SCEN="FAIL"
        echo "soak: the scenarios failed at ${ELAPSED}s — $SUBS subscribers, $DEAD dead rows, $SIZE" >&2
      else
        SCEN="harness"
        echo "soak: the run did not produce results at ${ELAPSED}s — the harness, not the scenarios" >&2
      fi
    fi
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$ELAPSED" "$(date -Iseconds)" "$HOST_UP" "$PG_UP" "$BR_UP" "$SUBS" "${LIVE:--}" "${DEAD:--}" "${SIZE:--}" "${AV_N:--}" "${AV_AT:--}" "$SCEN" >> "$OUT"
  tail -1 "$OUT"

  sleep "$SAMPLE"
done
