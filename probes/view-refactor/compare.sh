#!/usr/bin/env bash
# TWO SERVERS, ONE DATABASE, ONE SUBSCRIBER — and the question is whether B-96's refactor changed a
# single byte a client would receive.
#
# The old server (the tree before the refactor) and the new one share a database, so the state is
# arranged ONCE, through the product's own paths, and then both are asked to draw it. Any difference
# in the answers is a difference in code, because there is no other variable left.
#
# The tokens are interchangeable: both containers carry the same JWT_SECRET.
set -euo pipefail

NEW=${NEW:-http://127.0.0.1:8080}
OLD=${OLD:-http://127.0.0.1:8082}
OUT=${OUT:-/tmp/view-refactor}
rm -rf "$OUT"; mkdir -p "$OUT/new" "$OUT/old"

api() { curl -sS -H "Content-Type: application/json" "$@"; }

MSISDN="1555$(( RANDOM % 9000000 + 1000000 ))"
echo "subscriber: $MSISDN"

api -X POST "$NEW/api/v1/auth/otp/request" -d "{\"msisdn\":\"$MSISDN\"}" >/dev/null
CODE=$(api "$NEW/api/v1/dev/otp?msisdn=$MSISDN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["code"])')
TOKEN=$(api -X POST "$NEW/api/v1/auth/otp/verify" -d "{\"msisdn\":\"$MSISDN\",\"code\":\"$CODE\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
auth=(-H "Authorization: Bearer $TOKEN")

# MONEY THROUGH THE TOP-UP SAGA, not an UPDATE at the database: a precondition arranged behind the
# application proves the rest works given a state nothing can reach.
api -X POST "$NEW/api/v1/top-ups" "${auth[@]}" -d '{"amountMinor":20000}' >/dev/null
sleep 2

buy() {
  local id
  id=$(api -X POST "$NEW/api/v1/purchases" "${auth[@]}" -d "{\"planId\":\"$1\"}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["orderId"])')
  api -X POST "$NEW/api/v1/purchases/$id/confirm" "${auth[@]}" >/dev/null
  echo "$id"
}

# A HOME BUNDLE so there are counters, and TWO ZONES so the travel screen has something to order.
ORDER=$(buy home-20gb-30d)
buy tr-10gb-30d >/dev/null
buy eu-5gb-14d >/dev/null

# A CHANGE ASKED FOR AND NOT CONFIRMED, which is what puts the pending banner on the profile and on
# the catalogue — the sentence the two screens used to compose separately.
CHANGE=$(api -X POST "$NEW/api/v1/tariff-changes" "${auth[@]}" -d '{"tariffId":"tr-max"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["changeId"])')
sleep 2

paths=(
  "/api/v1/screens/home"
  "/api/v1/screens/profile"
  "/api/v1/screens/roaming"
  "/api/v1/screens/tariffs"
  "/api/v1/screens/tariff-changes/$CHANGE"
  "/api/v1/screens/plans"
  "/api/v1/screens/plans/home-20gb-30d"
  "/api/v1/screens/orders/$ORDER"
  "/api/v1/navigation"
)

fail=0
for p in "${paths[@]}"; do
  name=$(echo "$p" | tr '/' '_')
  # Pretty-printed with sorted keys: a difference in key ORDER is not a difference a client can see,
  # and comparing raw bytes would report one.
  api "$NEW$p" "${auth[@]}" | python3 -m json.tool --sort-keys > "$OUT/new/$name.json" 2>/dev/null || echo "NEW $p unreadable"
  api "$OLD$p" "${auth[@]}" | python3 -m json.tool --sort-keys > "$OUT/old/$name.json" 2>/dev/null || echo "OLD $p unreadable"
  if diff -q "$OUT/old/$name.json" "$OUT/new/$name.json" >/dev/null 2>&1; then
    echo "same     $p"
  else
    echo "DIFFERS  $p"
    diff "$OUT/old/$name.json" "$OUT/new/$name.json" | head -40
    fail=1
  fi
done

# A GUARD ON THE PROBE ITSELF. Two empty files are identical, and a run where every fetch failed
# would report nine screens in perfect agreement.
for f in "$OUT"/new/*.json; do
  [ -s "$f" ] || { echo "EMPTY: $f — this run compared nothing"; fail=1; }
done
echo "captured: $(ls "$OUT"/new/*.json | wc -l) screens, $(wc -c < "$OUT/new/_api_v1_screens_home.json") bytes on home"
exit $fail
