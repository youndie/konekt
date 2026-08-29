#!/usr/bin/env bash
# THE CHART'S GUARDS, EXERCISED — because until `B-91` nothing ran them.
#
# `charts/konekt/templates` refuses a render five ways: a missing database password, a missing image
# version, a missing hostname, the simulator above one replica, and any replica count above one. Each
# is a `fail` with a sentence explaining what would break, and each of them fired for the first time
# in front of whoever was deploying — no CI job and no make target ever rendered this chart.
#
# A guard nobody runs is a comment with a syntax error in it. This runs them, both ways: the valid
# configuration must render, and every refusal must refuse FOR ITS OWN REASON — a template that failed
# for an unrelated typo would otherwise pass every negative case here.
set -euo pipefail

cd "$(dirname "$0")/.."

CHART=charts/konekt
# The smallest configuration that is allowed to render. Everything absent from this list is either
# defaulted in `values.yaml` or is one of the things the chart refuses to guess.
VALID=(--set jwtSecret=ci --set postgres.password=ci --set server.version=v0.0.0 --set hostname=ci.example)

fail=0

renders() {
  local what=$1; shift
  if helm template konekt "$CHART" "$@" >/dev/null 2>&1; then
    echo "ok    $what renders"
  else
    echo "FAIL  $what does not render, and it must:"
    helm template konekt "$CHART" "$@" 2>&1 | tail -3 | sed 's/^/        /'
    fail=1
  fi
}

# The message is matched, not just the failure. A chart that broke for an unrelated reason would
# otherwise satisfy every one of these — which is the shape of vacuity this repository keeps finding.
refuses() {
  local what=$1 expected=$2; shift 2
  local output
  if output=$(helm template konekt "$CHART" "$@" 2>&1); then
    echo "FAIL  $what rendered, and it must not"
    fail=1
  elif ! grep -q "$expected" <<<"$output"; then
    echo "FAIL  $what was refused for the wrong reason — expected \"$expected\", got:"
    tail -3 <<<"$output" | sed 's/^/        /'
    fail=1
  else
    echo "ok    $what is refused, naming \"$expected\""
  fi
}

renders "the ordinary single-instance deployment" "${VALID[@]}"

refuses "two replicas" "single-instance deployment" \
  "${VALID[@]}" --set server.replicas=2
refuses "the simulator above one replica" "simulateTraffic is on" \
  "${VALID[@]}" --set server.replicas=2 --set simulateTraffic=true
refuses "a missing database password" "postgres.password is required" \
  --set jwtSecret=ci --set server.version=v0.0.0 --set hostname=ci.example
refuses "a missing image version" "server.version is required" \
  --set jwtSecret=ci --set postgres.password=ci --set hostname=ci.example
refuses "a missing hostname" "hostname is required" \
  --set jwtSecret=ci --set postgres.password=ci --set server.version=v0.0.0

exit $fail
