#!/usr/bin/env bash
# THE OBSERVABILITY SWITCH (`B-117`, measurement 5): restart the server with tracy and metrik pointed
# at nothing, or back at the collectors. Their agents answer a missing endpoint by doing nothing,
# which is exactly the switch — the same image, the same limits, the same everything else.
#
#     scripts/measure/observe.sh off     # then run the profile
#     scripts/measure/observe.sh on      # and run it again
#
# With the collectors off, metrik cannot be the oracle; the comparison is on k6's timings and the
# server's CPU, both of which are recorded either way.
set -euo pipefail
cd "$(dirname "$0")/../.."
MODE=${1:?usage: observe.sh on|off}
PROJECT=${PROJECT:-konekt}
MEASURE_HOME=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}
ENV_FILE=$MEASURE_HOME/env
COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml -f deploy/compose.measure.yaml --env-file "$ENV_FILE")

sed -i '/^METRIK_ENDPOINT=/d; /^TRACY_ENDPOINT=/d' "$ENV_FILE"
if [ "$MODE" = off ]; then
  # `${VAR-default}` in the compose takes an EMPTY value as a value: the agents get no endpoint.
  printf 'METRIK_ENDPOINT=\nTRACY_ENDPOINT=\n' >> "$ENV_FILE"
fi
"${COMPOSE[@]}" up -d --no-build --wait server >/dev/null
"${COMPOSE[@]}" exec server bash -c 'echo "METRIK_ENDPOINT=[$METRIK_ENDPOINT] TRACY_ENDPOINT=[$TRACY_ENDPOINT]"'
echo "observability $MODE; the server restarted — warm it up before measuring"
