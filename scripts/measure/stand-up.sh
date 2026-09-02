#!/usr/bin/env bash
# THE MEASUREMENT STAND, from a fresh box to a running compose with the chart's limits (`B-117`).
#
#     scripts/measure/stand-up.sh ghcr.io/youndie/konekt-server:v0.1.40
#
# Run from a checkout of this repository (a `rsync` of `deploy/`, `scripts/measure/` and the
# Makefile is enough) on the box that will host the stand. What it does, in order: makes sure docker
# and the compose plugin are there (installs them on Debian/Ubuntu if not), binds every host port
# the base compose opens to loopback, pulls the images, starts the stand with `compose.measure.yaml`
# layered on, seeds katcher's key, pulls the k6 image, and prints how to run a scenario.
#
# It never builds: the image is the argument, and a measurement of an image nobody can name again
# is not a measurement.
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${1:?usage: stand-up.sh <server image, e.g. ghcr.io/youndie/konekt-server:v0.1.40>}
# THE PROJECT NAME IS EXPLICIT, and the environment file lives OUTSIDE the tree. The name, because
# every other script here addresses containers as `<project>-server-1` and the Makefile's stand
# takes its name from the directory; the file, because on the build box this tree is a mutagen
# replica and a file written into it is deleted by the next sync — which is how the first dry run
# ended with "couldn't find env file" ten seconds after writing it.
PROJECT=${PROJECT:-konekt}
MEASURE_HOME=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}
mkdir -p "$MEASURE_HOME/out"
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml -f deploy/compose.measure.yaml)
ENV_FILE=$MEASURE_HOME/env

if ! command -v docker >/dev/null 2>&1; then
  # Ubuntu's own packages rather than Docker's repository: a fresh release (26.04 on the rented
  # boxes) is in the distribution before it is in Docker's list, and a stand script that fails on
  # the newest Ubuntu is a script that fails on the box somebody just rented.
  echo "installing docker from the distribution"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq docker.io docker-compose-v2 >/dev/null
  systemctl enable --now docker >/dev/null 2>&1 || true
fi
docker compose version >/dev/null

# EVERY HOST PORT ON LOOPBACK. The base file opens Postgres, both servers and the collectors on
# the host; on a rented box that host has a public address. The generator does not need any of
# them — it runs on the compose network — and a person needs them only through an ssh tunnel.
# Overridable one by one, because a box that already serves something on 8080 — the spare box did —
# refuses the container and the stand comes up without its server. `SIMULATE_TRAFFIC` is here too:
# the simulator ticks EVERY subscriber every five seconds, and a load stand that signed in fifty
# thousand of them is a stand whose simulator is the load (see the report).
cat > "$ENV_FILE" <<ENV
SERVER_IMAGE=$IMAGE
POSTGRES_PORT=${POSTGRES_PORT:-127.0.0.1:55432}
SERVER_PORT=${SERVER_PORT:-127.0.0.1:8080}
DECLINING_SERVER_PORT=${DECLINING_SERVER_PORT:-127.0.0.1:8081}
METRIK_PORT=${METRIK_PORT:-127.0.0.1:8190}
TRACY_PORT=${TRACY_PORT:-127.0.0.1:8191}
KATCHER_PORT=${KATCHER_PORT:-127.0.0.1:8192}
RELEASE=$IMAGE
SIMULATE_TRAFFIC=${SIMULATE_TRAFFIC:-true}
ENV

"${COMPOSE[@]}" --env-file "$ENV_FILE" pull -q
"${COMPOSE[@]}" --env-file "$ENV_FILE" up -d --no-build --wait
"${COMPOSE[@]}" --env-file "$ENV_FILE" --profile seed run --rm katcher-seed >/dev/null
docker pull -q grafana/k6:0.54.0 >/dev/null

echo
echo "stand is up on image $IMAGE with the chart's limits"
echo "  a scenario:   scripts/measure/k6.sh screens RATES=10,25,50 HOLD=120     (results in $MEASURE_HOME/out)"
echo "  the sampler:  systemd-run --unit=konekt-sample --collect $PWD/scripts/measure/sample.sh $MEASURE_HOME/out 60"
echo "  the stand:    ${COMPOSE[*]} --env-file $ENV_FILE ps"
