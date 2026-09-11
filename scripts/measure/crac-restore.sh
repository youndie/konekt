#!/usr/bin/env bash
# THE GATE OF ZAVARNIK'S CRaC PHASE (zavarnik `B-32`): what a checkpoint of this server does with a
# connection pool underneath, and what a restore is worth.
#
#     scripts/measure/crac-restore.sh <phase>
#
# Phases, cheapest first; each one says what it expects to break:
#   h1  no policy at all              — expect CheckpointOpenSocketException on the pool's sockets
#   h2  listening socket reopened     — expect the pool's sockets to be what is left
#   h3  pool sockets closed by policy — does the checkpoint pass, and does the pool survive restore?
#   h4  h7 + Postgres restarted between checkpoint and restore
#   h5  h7 + the environment changed on the restore container (does it reach the process?)
#   h6  two restores of one image, side by side — the same random numbers? (zavarnik `B-33`)
#   h7  pool and broker sockets IGNORED rather than closed — does the pool notice on its own?
#   h8  h7 with a pool of one connection — is the checkpoint's race with the pool's adder the cause?
#   h9  h7 plus the round trip through the broker (`crac-probe.py`): plain server against restored
#   h10 the numbers: N plain starts against N restores, alternating — readiness and the first
#       signed-in screen, the way `aot-coldstart.sh` measures the cache
#
# The stand is the base compose file's Postgres, broker and migrations under a project name of its
# own, the way `aot-image.sh` brings them up; the server is a container of the CRaC image on that
# network with its port published, so the workload can be plain curl from the host.
#
# The image is built here rather than by `deploy/Dockerfile`, because the base has to be a JDK with
# CRaC: Azul Zulu is the only vendor shipping one for 25 (zavarnik `docs/research/research-crac.md`
# §1.1), and its `-jre-crac` image carries `jcmd`, which an ordinary JRE image does not.
set -uo pipefail
# ROOT lets the script run from a copy outside the tree (the Linux box keeps a replica).
ROOT=${ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
cd "$ROOT"
PHASE=${1:-h1}
BASE=${BASE:-azul/zulu-openjdk:25-jre-crac}
IMG=${IMG:-konekt-server:crac}
DIST=${DIST:-server/build/install/server}
PROJECT=${PROJECT:-konekt-crac}
PORT=${PORT:-18080}
MSISDN=${MSISDN:-+15559990001}
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml)
NET=${PROJECT}_default
CR=${CR:-/tmp/konekt-crac-image}
PROBE=${PROBE:-scripts/measure/crac-probe.py}   # the round trip through the broker; overridable for a copy outside the tree
CP=konekt-crac-cp
RS=konekt-crac-rs

log() { echo "[$(date +%H:%M:%S)] $*"; }
now_ms() { date +%s%3N; }
api() { curl -s -m 10 "$@"; }
json() { python3 -c 'import sys,json;d=json.load(sys.stdin);print(d'"$1"')' 2>/dev/null; }

build_image() {
  test -d "$DIST/lib" || { echo "no distribution at $DIST — run ./gradlew :server:installDist"; exit 1; }
  local ctx; ctx=$(mktemp -d); cp -r "$DIST/." "$ctx/"
  cat > "$ctx/Dockerfile" <<EOF
FROM $BASE
WORKDIR /opt/konekt
COPY . ./
RUN useradd --system --uid 10001 konekt && chown -R konekt:konekt /opt/konekt && mkdir -p /cr && chown konekt:konekt /cr
USER konekt
EXPOSE 8080
ENTRYPOINT ["./bin/server"]
EOF
  docker build -q -t "$IMG" "$ctx" >/dev/null && rm -rf "$ctx"
  log "image $IMG on $BASE: $(docker image ls --format '{{.Size}}' "$IMG" | head -1)"
}

stand_up() {
  SERVER_IMAGE=$IMG "${COMPOSE[@]}" up -d --wait postgres broker >/dev/null
  SERVER_IMAGE=$IMG "${COMPOSE[@]}" up --exit-code-from migrate migrate >/dev/null 2>&1
  log "stand up: postgres, broker, migrations"
}
stand_down() { SERVER_IMAGE=$IMG "${COMPOSE[@]}" down -v >/dev/null 2>&1; }

# The environment the compose file gives the server, as plain flags. The traffic simulator is ON by
# default (`SIM=false` turns it off, which is how the eSIM identifiers were compared without the
# simulator drawing from the same generators):
# the realtime updates the probe waits for are usage events, and without it the probe's stream is
# empty on a plain start too — which is what the first h9 run measured.
env_flags() {
  echo "-e DB_URL=jdbc:postgresql://postgres:5432/konekt -e DB_USER=konekt -e DB_PASSWORD=konekt \
-e JWT_SECRET=dev-secret-not-for-anything-real -e BROKER_HOST=broker -e BROKER_PORT=9092 \
-e DEV_REVEAL_OTP=true -e DEV_SCREENS=true -e SIMULATE_TRAFFIC=${SIM:-true}"
}

wait_ready() { # container -> ms to the first 200 on /health
  local t0; t0=$(now_ms)
  for _ in $(seq 1 900); do
    curl -sf -o /dev/null -m 2 "http://127.0.0.1:$PORT/health" && { echo $(( $(now_ms) - t0 )); return 0; }
    docker ps --format '{{.Names}}' | grep -qx "$1" || { echo "died"; return 1; }
    sleep 0.05
  done
  echo timeout; return 1
}

sign_in() { # prints the bearer token
  api -X POST -H 'content-type: application/json' -d "{\"msisdn\":\"$MSISDN\"}" \
    "http://127.0.0.1:$PORT/api/v1/auth/otp/request" >/dev/null
  local code; code=$(api "http://127.0.0.1:$PORT/api/v1/dev/otp?msisdn=$(printf %s "$MSISDN" | sed 's/+/%2B/')" | json '["code"]')
  api -X POST -H 'content-type: application/json' -d "{\"msisdn\":\"$MSISDN\",\"code\":\"$code\"}" \
    "http://127.0.0.1:$PORT/api/v1/auth/otp/verify" | json '["accessToken"]'
}

warm() { # token, passes — the k6 `screens` hot path, the same one the AOT training uses
  local token=$1 passes=${2:-20}
  for _ in $(seq 1 "$passes"); do
    for screen in home plans plans/tr-10gb-30d; do
      api -o /dev/null -H "Authorization: Bearer $token" "http://127.0.0.1:$PORT/api/v1/screens/$screen"
    done
  done
}

screen_ms() { # token -> ms of one home screen, and its status (a DB read behind it)
  local t0 code; t0=$(now_ms)
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H "Authorization: Bearer $1" "http://127.0.0.1:$PORT/api/v1/screens/home")
  echo "$(( $(now_ms) - t0 ))ms $code"
}

policy_file() { # writes the policy for the phase, or nothing
  local f=$CR/policies.yaml
  case $1 in
    h1) rm -f "$f" ;;
    h2) cat > "$f" <<'EOF'
type: SOCKET
listening: true
action: reopen
EOF
      ;;
    h7|h8) cat > "$f" <<'EOF'
type: SOCKET
listening: true
action: reopen
---
type: SOCKET
remotePort: 5432
action: ignore
---
type: SOCKET
remotePort: 9092
action: ignore
---
type: FILEDESCRIPTOR
action: ignore
EOF
      ;;
    *) cat > "$f" <<'EOF'
type: SOCKET
listening: true
action: reopen
---
type: SOCKET
remotePort: 5432
action: close
---
type: SOCKET
remotePort: 9092
action: close
EOF
      ;;
  esac
  test -f "$f" && chmod 644 "$f"
}

checkpoint() { # phase -> leaves the image in $CR, prints what the JVM said
  rm -rf "$CR"; mkdir -p "$CR"; chmod 777 "$CR"
  policy_file "$1"
  local opts="-XX:CRaCCheckpointTo=/cr -Djdk.crac.collect-fd-stacktraces=true"
  [ -f "$CR/policies.yaml" ] && opts="$opts -Djdk.crac.resource-policies=/cr/policies.yaml"
  docker rm -f $CP >/dev/null 2>&1
  # shellcheck disable=SC2046
  docker run -d --name $CP --network "$NET" -p $PORT:8080 $(env_flags) ${POOL:+-e DB_POOL_SIZE=$POOL} \
    -e JAVA_OPTS="$opts" -v "$CR:/cr" "$IMG" >/dev/null
  log "server up for the checkpoint: $(wait_ready $CP) ms to /health"
  local token; token=$(sign_in)
  [ -n "$token" ] || { echo "no token — the workload cannot run"; docker logs $CP 2>&1 | tail -5; return 1; }
  warm "$token" 20
  log "warmed: 20 passes over three screens; pool has served them"
  echo "-- jcmd JDK.checkpoint:"
  docker exec $CP jcmd io.konekt.ApplicationKt JDK.checkpoint 2>&1 | grep -v $'^\t*at ' | head -30
  sleep 4
  echo "-- container log tail:"
  docker logs $CP 2>&1 | grep -iv "^\s*at \|LoggerContainer info" | tail -12
  echo "-- image: $(ls "$CR" 2>/dev/null | grep -v policies | wc -l) files, $(du -sh "$CR" 2>/dev/null | cut -f1)"
  docker rm -f $CP >/dev/null 2>&1
}

restore() { # name, extra docker flags -> prints readiness and the first screen
  docker rm -f $RS >/dev/null 2>&1
  # shellcheck disable=SC2046
  docker run -d --name $RS --network "$NET" -p $PORT:8080 $(env_flags) ${POOL:+-e DB_POOL_SIZE=$POOL} "$@" \
    -v "$CR:/cr" --entrypoint java "$IMG" -XX:CRaCRestoreFrom=/cr >/dev/null
  local r; r=$(wait_ready $RS)
  echo "ready=${r}ms"
  local token; token=$(sign_in)
  if [ -n "$token" ]; then
    echo "  sign-in ok; home screen: $(screen_ms "$token"); second: $(screen_ms "$token")"
  else
    echo "  SIGN-IN FAILED (the pool, most likely) — log:"; docker logs $RS 2>&1 | tail -15
  fi
}

trap 'docker rm -f $CP $RS >/dev/null 2>&1' EXIT
build_image
stand_up
echo "=== phase $PHASE"
[ "$PHASE" = h8 ] && POOL=${POOL:-1}
case $PHASE in
  h9)
    checkpoint h7
    if [ "$(ls "$CR" | grep -vc policies)" -gt 0 ]; then
      echo "-- control: the same probe against a plain start of the same image"
      docker rm -f $CP >/dev/null 2>&1
      # shellcheck disable=SC2046
      docker run -d --name $CP --network "$NET" -p $PORT:8080 $(env_flags) "$IMG" >/dev/null
      wait_ready $CP >/dev/null
      python3 "$PROBE" --base "http://127.0.0.1:$PORT" --stream-seconds 20
      echo "   control exit=$?"
      docker rm -f $CP >/dev/null 2>&1
      echo "-- restored:"
      restore
      python3 "$PROBE" --base "http://127.0.0.1:$PORT" --stream-seconds 20
      echo "   restored exit=$?"
      echo "-- restored container log, anything about the pool or the broker:"
      docker logs $RS 2>&1 | grep -iE "hikari|booblik|broker|outbox|SocketException|Connection" | tail -15
      docker rm -f $RS >/dev/null 2>&1
    fi
    ;;
  h10)
    checkpoint h7
    [ "$(ls "$CR" | grep -vc policies)" -gt 0 ] || { echo "no image, nothing to measure"; exit 1; }
    N=${N:-10}
    echo "variant,run,ready_ms,first_screen_ms,status"
    for i in $(seq 1 "$N"); do
      docker rm -f $CP >/dev/null 2>&1
      # shellcheck disable=SC2046
      docker run -d --name $CP --network "$NET" -p $PORT:8080 $(env_flags) "$IMG" >/dev/null
      r=$(wait_ready $CP); t=$(sign_in); echo "plain,$i,$r,$(screen_ms "$t")" | tr ' ' ','
      docker rm -f $CP >/dev/null 2>&1
      docker rm -f $RS >/dev/null 2>&1
      # shellcheck disable=SC2046
      docker run -d --name $RS --network "$NET" -p $PORT:8080 $(env_flags) -v "$CR:/cr" \
        --entrypoint java "$IMG" -XX:CRaCRestoreFrom=/cr >/dev/null
      r=$(wait_ready $RS); t=$(sign_in); echo "restore,$i,$r,$(screen_ms "$t")" | tr ' ' ','
      docker rm -f $RS >/dev/null 2>&1
    done
    ;;
  h1|h2|h3|h7|h8)
    checkpoint "$PHASE"
    if [ -s "$CR/core-1.img" ] || [ "$(ls "$CR" | grep -vc policies)" -gt 0 ]; then
      echo "-- restore:"; restore
      docker rm -f $RS >/dev/null 2>&1
    fi
    ;;
  h4)
    checkpoint h7
    log "restarting Postgres between checkpoint and restore"
    SERVER_IMAGE=$IMG "${COMPOSE[@]}" restart postgres >/dev/null
    sleep 5
    echo "-- restore after the database was restarted:"; restore
    ;;
  h5)
    checkpoint h7
    echo "-- restore with a different BRAND and DEV_SCREENS in the environment:"
    restore -e BRAND=brand-b -e DEV_SCREENS=false
    echo "  BRAND the process reports: $(api "http://127.0.0.1:$PORT/api/v1/screens/home" -H "Authorization: Bearer $(sign_in)" | head -c 200)"
    ;;
  h6)
    # Two restores of one image, each signing in as a DIFFERENT subscriber, both walking into the
    # eSIM wizard — whose mock issues ICCIDs and matching ids from `kotlin.random.Random.Default`
    # (`MockSmDpPlus`). Different subscribers, so anything identical in the two answers came from
    # the JVM's generator and not from the database.
    checkpoint h7
    for n in 1 2; do
      echo "-- restore $n:"
      MSISDN="+1555${n}00$RANDOM"
      restore
      token=$(sign_in)
      api -X POST -H "Authorization: Bearer $token" "http://127.0.0.1:$PORT/api/v1/esim-wizard" > "$CR/wizard-$n.json"
      echo "   wizard screen: $(wc -c < "$CR/wizard-$n.json") bytes; identifiers in it:"
      grep -oE '[0-9]{15,20}|LPA:1[^"]*|"[0-9a-fA-F]{8}"' "$CR/wizard-$n.json" | sort -u | head -5 | sed 's/^/     /'
      docker rm -f $RS >/dev/null 2>&1
    done
    echo "-- the two wizard screens differ in:"
    diff <(tr ',' '\n' < "$CR/wizard-1.json") <(tr ',' '\n' < "$CR/wizard-2.json") | head -12 || true
    ;;
esac
log "done; stand stays up (PROJECT=$PROJECT). Take it down with: docker compose -p $PROJECT -f deploy/compose.yaml down -v"
