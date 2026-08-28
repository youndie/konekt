# HOW LONG A NEW SUBSCRIBER WAITS FOR THEIR COUNTER TO MOVE, as the stand fills up.
#
# The question B-77 asks and could not answer: does the wait grow with the number of subscribers the
# simulator is publishing for? Relative, on one stand, in one run — the absolute seconds belong to
# this machine and nothing else.
set -e
H=http://127.0.0.1:8080

subscriber() {   # sign in, top up, buy a home plan: a subscriber the simulator will publish for
  M="1555$RANDOM$RANDOM"
  curl -s -X POST "$H/api/v1/auth/otp/request" -H 'Content-Type: application/json' -d "{\"msisdn\":\"$M\"}" >/dev/null
  C=$(curl -s "$H/api/v1/dev/otp?msisdn=$M" | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])")
  T=$(curl -s -X POST "$H/api/v1/auth/otp/verify" -H 'Content-Type: application/json' -d "{\"msisdn\":\"$M\",\"code\":\"$C\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")
  curl -s -X POST "$H/api/v1/top-ups" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"amountMinor":5000}' >/dev/null
  O=$(curl -s -X POST "$H/api/v1/purchases" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"planId":"home-20gb-30d"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['orderId'])")
  curl -s -X POST "$H/api/v1/purchases/$O/confirm" -H "Authorization: Bearer $T" >/dev/null
  echo "$T"
}

counter() { curl -s "$H/api/v1/screens/home" -H "Authorization: Bearer $1" | python3 -c "
import json,sys
d=json.load(sys.stdin); out=[]
def w(n):
    if isinstance(n,dict):
        if n.get('id')=='counter-data': out.append(n.get('valueText'))
        for v in n.values(): w(v)
    elif isinstance(n,list):
        for v in n: w(v)
w(d); print(out[0] if out else 'none')"; }

lag() {          # seconds from "this subscriber exists" to "their data counter has moved"
  T=$(subscriber); FIRST=$(counter "$T"); START=$(date +%s)
  for i in $(seq 1 150); do
    [ "$(counter "$T")" != "$FIRST" ] && { echo $(( $(date +%s) - START )); return; }
    sleep 1
  done
  echo 150
}

loaded() { docker compose -f deploy/compose.yaml exec -T postgres psql -U konekt -d konekt -tAc \
  "SELECT count(DISTINCT subscriber_id) FROM usage_counter" 2>/dev/null | tr -d ' \r'; }

echo "BOOT-AT-START $(cut -d. -f1 /proc/uptime)s uptime, stand: $(docker compose -f deploy/compose.yaml ps --format '{{.Service}} {{.RunningFor}}' | head -1)"
for TARGET in 0 20 40 80; do
  while [ "$(loaded)" -lt "$TARGET" ]; do subscriber >/dev/null; done
  # Three readings per point: one is an anecdote, and this one has a broker in it.
  R1=$(lag); R2=$(lag); R3=$(lag)
  echo "RESULT subscribers=$(loaded) lag=${R1}s,${R2}s,${R3}s uptime=$(cut -d. -f1 /proc/uptime)s"
done
