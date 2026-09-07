#!/usr/bin/env bash
# COLD START WITH AND WITHOUT THE AOT CACHE, JIB PATH (`B-123`). The same measurement as
# aot-coldstart.sh over the Jib image instead of the Dockerfile one: zavarnik's `jibAotTrain` trains
# inside a container of the Jib image on the stand's network, the next Jib build carries the cache
# as a layer with -XX:AOTCache in the entrypoint, `jibAotVerify` checks it there, and coldstart.sh
# runs against the two images in alternation. Run on the stand box:
#
#     scripts/measure/aot-coldstart-jib.sh [restarts per round: 5] [rounds: 2]
#
# The image without the cache is tagged `konekt-server-jib:nocache` before the second Jib build
# retags `latest`, so both are named and the alternation can switch between them.
#
# `--no-configuration-cache` on every Jib invocation: Jib 3.5.4 reads `Task.project` at execution
# time, which the configuration cache this build has on refuses. Only these three invocations.
set -euo pipefail
cd "$(dirname "$0")/../.."
RUNS=${1:-5}
ROUNDS=${2:-2}
PROJECT=${PROJECT:-konekt}
BASE=${BASE:-http://127.0.0.1:8080}
MEASURE_HOME=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}
OUT=$MEASURE_HOME/aot-jib
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml -f deploy/compose.measure.yaml)
# What the compose file gives the server, handed to the training container the same way.
AOT_DOCKER="--network ${PROJECT}_default -e DB_URL=jdbc:postgresql://postgres:5432/konekt -e DB_USER=konekt -e DB_PASSWORD=konekt -e JWT_SECRET=dev-secret-not-for-anything-real -e BROKER_HOST=broker -e BROKER_PORT=9092 -e DEV_REVEAL_OTP=true -e DEV_SCREENS=true -e BRAND=brand-a -e PAYMENT_MOCK_MODE=approve -e PAYMENT_MOCK_DELAY_MS=0"
rm -rf "$OUT" server/build/zavarnik/jib; mkdir -p "$OUT"

echo "== the Jib image without a cache, and the stand on it"
./gradlew :server:jibDockerBuild -q --console=plain --no-configuration-cache
docker tag konekt-server-jib:latest konekt-server-jib:nocache
SERVER_IMAGE=konekt-server-jib:nocache "${COMPOSE[@]}" up -d --no-build --wait

echo "== jibAotTrain: training inside konekt-server-jib on the stand's network"
./gradlew :server:jibAotTrain -q --console=plain --no-configuration-cache "-Pkonekt.aotDocker=$AOT_DOCKER"
cp server/build/zavarnik/jib/* "$OUT/" 2>/dev/null || true
ls -l server/build/zavarnik/jib/

echo "== jibAotVerify: the Jib build with the cache, verified inside it"
./gradlew :server:jibAotVerify -q --console=plain --no-configuration-cache "-Pkonekt.aotDocker=$AOT_DOCKER" | tee "$OUT/verify.txt"
docker inspect konekt-server-jib:latest --format '  entrypoint {{json .Config.Entrypoint}}'
docker image ls konekt-server-jib --format '  {{.Repository}}:{{.Tag}} {{.Size}}'

for round in $(seq 1 "$ROUNDS"); do
  for variant in nocache latest; do
    echo "== round $round: konekt-server-jib:$variant, $RUNS restarts"
    SERVER_IMAGE=konekt-server-jib:$variant "${COMPOSE[@]}" up -d --no-build --wait server
    docker inspect --format '  image {{.Config.Image}}' "${PROJECT}-server-1"
    # A Jib image declares no HEALTHCHECK, so `--wait` returns on "running", not on "serving" —
    # and coldstart.sh's sign-in against a server still starting fails silently under `curl -sf`.
    for i in $(seq 300); do curl -sf -o /dev/null "$BASE/health" && break; sleep 0.1; done
    curl -sf -o /dev/null "$BASE/health" || { echo "server never answered /health"; exit 1; }
    scripts/measure/coldstart.sh "$RUNS" "$BASE"
    cp "$MEASURE_HOME/out/coldstart.csv" "$OUT/coldstart-$variant-$round.csv"
  done
done

echo "== summary (medians over all rounds; every row is in $OUT/coldstart-*.csv)"
python3 - "$OUT" <<'PY'
import csv, glob, statistics, sys
out = sys.argv[1]
for variant in ("nocache", "latest"):
    rows = [r for f in sorted(glob.glob(f"{out}/coldstart-{variant}-[0-9]*.csv")) for r in csv.DictReader(open(f))]
    cols = ("start_to_healthy_ms", "first_ms", "p50_first100_ms", "p95_first100_ms")
    if not rows:
        print(f"  {variant:8s} no rounds")
        continue
    med = {c: statistics.median(float(r[c]) for r in rows) for c in cols}
    healthy = " ".join(sorted((r["start_to_healthy_ms"] for r in rows), key=float))
    print(f"  {variant:8s} n={len(rows):2d}  " + "  ".join(f"{c}={med[c]:.0f}" for c in cols) + "  healthy: " + healthy)
PY
