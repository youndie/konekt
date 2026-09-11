#!/usr/bin/env bash
# TWO SERVER IMAGES, ALTERNATED: what a change to the server's own code does to allocations.
#
#     A=konekt-server:ab-a B=konekt-server:ab-b [REPS=3] [RATE=200] [WARMUP=60] [MEASURE=120] \
#       scripts/measure/ab-images.sh
#
# The stand must already be up (`scripts/measure/stand-up.sh`) — this only restarts the `server`
# service with each image in turn, so Postgres, the broker and the seeded data stay as they were.
# Postgres is the reason the variants alternate rather than run one after the other: a cold page
# cache and a warm one differ by more than the effects worth measuring here.
#
# WHAT IT REPORTS AND WHY IT IS NOT THROUGHPUT. The scenario runs at a CONSTANT ARRIVAL RATE, so
# requests per second is an input; what varies is what each request costs. The output is therefore
# allocated bytes per request, from async-profiler's sampled total over a fixed window divided by
# the requests that window carried, plus p95 from k6 and the profile's own owner table. Sampled
# bytes are comparable between variants and are not an absolute — the same caveat every alloc
# figure in this repository's reports carries.
set -u
cd "$(dirname "$0")/../.."
A=${A:?usage: A=<image> B=<image> ab-images.sh}; B=${B:?usage: A=<image> B=<image> ab-images.sh}
REPS=${REPS:-3}; RATE=${RATE:-200}; WARMUP=${WARMUP:-60}; MEASURE=${MEASURE:-120}
PROJECT=${PROJECT:-konekt}; CONT=${CONT:-${PROJECT}-server-1}
ASPROF=${ASPROF:-$HOME/tools/async-profiler-4.5-linux-x64}
MEASURE_HOME=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}
ENV_FILE=$MEASURE_HOME/env
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml -f deploy/compose.measure.yaml --env-file "$ENV_FILE")
OUT=${RESULTS:-$HOME/bench-results}/ab-images; rm -rf "$OUT"; mkdir -p "$OUT"

echo "# $(date -u +%FT%TZ) A=$A B=$B reps=$REPS rate=$RATE warmup=${WARMUP}s measure=${MEASURE}s" | tee "$OUT/summary.md"

run() { # $1 variant $2 rep $3 image
  local v=$1 r=$2 image=$3
  # The image is swapped in the env file the whole stand reads, so the variant is recorded where
  # anybody looking at the stand can see which one is running.
  sed -i "s|^SERVER_IMAGE=.*|SERVER_IMAGE=$image|; s|^RELEASE=.*|RELEASE=$image|" "$ENV_FILE"
  "${COMPOSE[@]}" up -d --no-build --wait --force-recreate server >/dev/null
  local pid; pid=$(docker exec "$CONT" sh -c 'pgrep -o java')
  docker cp "$ASPROF" "$CONT:/tmp/asprof" >/dev/null && docker exec -u root "$CONT" chmod -R a+rX /tmp/asprof

  local hold=$((WARMUP + MEASURE + 30))
  scripts/measure/k6.sh screens "RATES=$RATE" "HOLD=$hold" > "$OUT/$v.$r.k6.log" 2>&1 & local k6=$!
  sleep "$WARMUP"
  docker exec "$CONT" /tmp/asprof/bin/asprof -d "$MEASURE" -e alloc --total -o collapsed \
    -f /tmp/alloc.collapsed "$pid" >/dev/null 2>&1
  wait $k6
  docker cp "$CONT:/tmp/alloc.collapsed" "$OUT/$v.$r.alloc.collapsed" >/dev/null
  # k6's own numbers for the whole scenario, which is the window plus the warm-up: the dropped
  # iterations line is the one that says whether the arrival rate was actually held.
  grep -E "http_reqs|http_req_duration|dropped_iterations|checks" "$OUT/$v.$r.k6.log" \
    | sed "s/^/$v rep$r /" | tee -a "$OUT/summary.md"
}

for r in $(seq "$REPS"); do
  for v in A B; do
    image=${!v}
    echo "## $v rep$r — $image" | tee -a "$OUT/summary.md"
    run "$v" "$r" "$image"
  done
done

echo "# raw stacks and k6 logs in $OUT"
