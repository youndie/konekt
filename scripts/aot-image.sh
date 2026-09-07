#!/usr/bin/env bash
# THE RELEASE IMAGE WITH ITS AOT CACHE (`B-123`): train inside a container of the image, lay the
# cache over it as one more layer, verify it there. What comes out is the image that gets published;
# what goes in is the image deploy/Dockerfile produced from `installDist`.
#
#     scripts/aot-image.sh <base image> <final image>
#     scripts/aot-image.sh konekt-server:release ghcr.io/youndie/konekt-server:v0.1.41
#
# Why the training is not a Dockerfile stage: the application does not start without Postgres and
# the broker, so the training run needs the stand's network and environment — `deploy/compose.yaml`
# provides both, and the runner zavarnik ships in `lib/` does the rest (readiness, the signed-in
# workload, SIGTERM, the manifest) on the image's own JVM, which is the only JVM the cache is good
# for. The jar mtimes the JVM checks against the cache are pinned by the plugin in `installDist`
# and preserved by the Dockerfile's COPY, so the second layer changes nothing the cache recorded.
#
# The stand it brings up is the base compose file's Postgres, broker and migrations — no server, no
# collectors — under an explicit project name, and it takes them down again at the end. Runs where
# there is Docker and the built base image: CI's `image` job, or `make release-image` on a laptop.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=${1:?usage: aot-image.sh <base image> <final image>}
FINAL=${2:?usage: aot-image.sh <base image> <final image>}
PROJECT=${PROJECT:-konekt-aot-train}
RUNNER="java -cp /opt/konekt/lib/zavarnik-runner.jar io.github.youndie.zavarnik.runner.Main"
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml)
OUT=$(mktemp -d)
chmod 777 "$OUT"   # the image's user (uid 10001) writes the cache here
cleanup() { SERVER_IMAGE=$BASE "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true; rm -rf "$OUT"; }
trap cleanup EXIT

echo "== the stand the training needs: postgres, broker, migrations — on $BASE"
SERVER_IMAGE=$BASE "${COMPOSE[@]}" up -d --wait postgres broker
SERVER_IMAGE=$BASE "${COMPOSE[@]}" up --exit-code-from migrate migrate

echo "== training inside $BASE"
SERVER_IMAGE=$BASE "${COMPOSE[@]}" run --rm --no-deps --entrypoint bash -v "$OUT:/out" server -c "
  set -e
  $RUNNER train /opt/konekt --out /out
  cp /opt/konekt/lib/app.aot /opt/konekt/lib/app.aot.jars /out/ 2>/dev/null || true
  ls -l /out/app.aot"
test -s "$OUT/app.aot" || { echo "no cache came out of the training run"; exit 1; }

echo "== $FINAL = $BASE plus the cache"
printf 'FROM %s\nCOPY --chown=10001:10001 app.aot app.aot.jars /opt/konekt/lib/\n' "$BASE" > "$OUT/Dockerfile"
docker build -q -t "$FINAL" "$OUT" >/dev/null

echo "== verifying inside $FINAL under -XX:AOTMode=on"
SERVER_IMAGE=$FINAL "${COMPOSE[@]}" run --rm --no-deps --entrypoint bash server -c "$RUNNER verify /opt/konekt --out /tmp"
for image in "$BASE" "$FINAL"; do docker image ls --format '  {{.Repository}}:{{.Tag}} {{.Size}}' "$image"; done
