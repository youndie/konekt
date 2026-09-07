#!/usr/bin/env bash
# COLD START WITH AND WITHOUT THE AOT CACHE (`B-123`). Builds the distribution and the stand image,
# trains a Leyden AOT cache INSIDE that image on the stand's network — the application does not
# start without Postgres and the broker — lays the cache over the image as one more layer, has the
# runner verify it under -XX:AOTMode=on in that second image, and then runs coldstart.sh against the
# two images in alternation. Run on the stand box:
#
#     scripts/measure/aot-coldstart.sh [restarts per round: 5] [rounds: 2]
#
# Alternation rather than five of one and five of the other: the box's clock and caches drift over
# minutes, and a variant measured entirely after the other is measured on a different machine.
#
# The JVM compares jar mtimes with the cache, and the runner pins them inside its own container,
# which the image never sees — so the plugin pins them in `installDist` itself (zavarnik B-28), the
# image is built from that, and Docker COPY keeps them. The first version of this script did it
# here with `touch -d @86400`; the constant belongs to the plugin, not to this script.
set -euo pipefail
cd "$(dirname "$0")/../.."
RUNS=${1:-5}
ROUNDS=${2:-2}
PROJECT=${PROJECT:-konekt}
BASE=${BASE:-http://127.0.0.1:8080}
MEASURE_HOME=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}
OUT=$MEASURE_HOME/aot
RUNNER="java -cp /opt/konekt/lib/zavarnik-runner.jar io.github.youndie.zavarnik.runner.Main"
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml -f deploy/compose.measure.yaml)
rm -rf "$OUT"; mkdir -p "$OUT"; chmod 777 "$OUT"   # the image's user (uid 10001) writes here

echo "== distribution (jar mtimes pinned by the plugin), stand image"
./gradlew :server:installDist -q --console=plain
ls -l --time-style=+%s server/build/install/server/lib/zavarnik-runner.jar | awk '{print "  runner jar mtime " $6 " (expected 86400)"}'
SERVER_IMAGE=konekt-server:local "${COMPOSE[@]}" up -d --build --wait

echo "== training inside konekt-server:local, on the stand's network"
"${COMPOSE[@]}" run --rm --no-deps --entrypoint bash -v "$OUT:/out" server -c "
  set -e
  $RUNNER train /opt/konekt
  cp /opt/konekt/lib/app.aot /opt/konekt/lib/app.aot.jars /out/
  cp /opt/konekt/lib/zavarnik-train*.log /out/ 2>/dev/null || true
  ls -l /out/app.aot"

echo "== konekt-server:local-aot = the same image plus the cache"
printf 'FROM konekt-server:local\nCOPY --chown=10001:10001 app.aot app.aot.jars /opt/konekt/lib/\n' > "$OUT/Dockerfile"
docker build -q -t konekt-server:local-aot "$OUT" >/dev/null
docker image ls konekt-server --format '  {{.Repository}}:{{.Tag}} {{.Size}}'

echo "== verify under -XX:AOTMode=on inside konekt-server:local-aot"
SERVER_IMAGE=konekt-server:local-aot "${COMPOSE[@]}" run --rm --no-deps --entrypoint bash server -c "$RUNNER verify /opt/konekt" | tee "$OUT/verify.txt"

for round in $(seq 1 "$ROUNDS"); do
  for variant in local local-aot; do
    echo "== round $round: konekt-server:$variant, $RUNS restarts"
    SERVER_IMAGE=konekt-server:$variant "${COMPOSE[@]}" up -d --no-build --wait server
    docker inspect --format '  image {{.Config.Image}}' "${PROJECT}-server-1"
    scripts/measure/coldstart.sh "$RUNS" "$BASE"
    cp "$MEASURE_HOME/out/coldstart.csv" "$OUT/coldstart-$variant-$round.csv"
  done
done

echo "== summary (medians over all rounds; every row is in $OUT/coldstart-*.csv)"
python3 - "$OUT" <<'PY'
import csv, glob, statistics, sys
out = sys.argv[1]
for variant in ("local", "local-aot"):
    rows = [r for f in sorted(glob.glob(f"{out}/coldstart-{variant}-[0-9]*.csv")) for r in csv.DictReader(open(f))]
    cols = ("start_to_healthy_ms", "first_ms", "p50_first100_ms", "p95_first100_ms")
    if not rows:
        print(f"  {variant:10s} no rounds")
        continue
    med = {c: statistics.median(float(r[c]) for r in rows) for c in cols}
    healthy = " ".join(sorted((r["start_to_healthy_ms"] for r in rows), key=float))
    print(f"  {variant:10s} n={len(rows):2d}  " + "  ".join(f"{c}={med[c]:.0f}" for c in cols) + "  healthy: " + healthy)
PY
