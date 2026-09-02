#!/usr/bin/env bash
# A CLEAN STAND (`B-117`): the same image, the same limits, an empty database and an empty broker.
# Between measurement groups, because every staircase point signs its own subscribers in and the
# stand accumulates them — thousands after a session — and the simulator, the consumer and the
# tables carry that weight into the next measurement as background nobody asked for.
#
#     scripts/measure/reset.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
PROJECT=${PROJECT:-konekt}
MEASURE_HOME=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}
ENV_FILE=$MEASURE_HOME/env
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml -f deploy/compose.measure.yaml --env-file "$ENV_FILE")
"${COMPOSE[@]}" --profile seed --profile declining down -v --remove-orphans >/dev/null
"${COMPOSE[@]}" up -d --no-build --wait >/dev/null
"${COMPOSE[@]}" --profile seed run --rm katcher-seed >/dev/null
echo "stand reset: empty database, empty broker, the same image and limits"
