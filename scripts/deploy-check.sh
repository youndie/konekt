#!/usr/bin/env bash
# WHAT THE CLUSTER RUNS, COMPARED WITH WHAT THE CHART SAYS — because `chart-check.sh` cannot.
#
# `chart-check.sh` renders `charts/konekt` and asserts things about that render. It is correct and it
# has always passed. `B-106` is what it cannot see: a deployment is a DIFFERENT render, produced by
# `helm upgrade` with flags the chart knows nothing about, and one of those flags quietly produces a
# render the chart would never emit.
#
# `--reuse-values` reuses the previous release's user-supplied config INSTEAD of coalescing with the
# new chart's `values.yaml`. A key the chart gained since the last deploy has no value in the old
# config, so it renders EMPTY. That is how this deployment ran with
#
#     BOOBLIK_RETENTION_BYTES=
#
# while `values.yaml` declared `134217728`, the broker deleted nothing, and every check was green.
#
# So this script asks the cluster, not the chart. It is not a CI gate and cannot be: CI has no
# cluster. It runs after a deploy, which is the only moment it has a subject.
set -euo pipefail

cd "$(dirname "$0")/.."

NAMESPACE=${NAMESPACE:-konekt}
RELEASE=${RELEASE:-konekt}
CHART=${CHART:-charts/konekt}

# A RENDER OF THE CURRENT CHART WITH THE OPERATOR'S OWN VALUES — which is exactly what
# `--reset-then-reuse-values` produces, and exactly what `--reuse-values` does not. `helm get values`
# without `--all` answers with the USER-SUPPLIED config alone, so the chart's defaults come from the
# working tree; asking for `--all` would answer with what the release computed, and comparing a
# release against itself proves nothing.
#
# Piped rather than written: these values carry the database password and the JWT secret, and a
# temporary file is a copy of both that outlives the check on a bad day.
render() {
  helm template "$RELEASE" "$CHART" --namespace "$NAMESPACE" \
    -f <(helm get values "$RELEASE" --namespace "$NAMESPACE" -o yaml)
}

live() {
  kubectl get deployments,statefulsets --namespace "$NAMESPACE" -o json
}

# THE COMPARISON LIVES IN `deploy_check.py`, and it is a separate file so that it can be RUN on
# inputs this shell function cannot produce. The assertion that actually caught `B-106` is "a value
# is empty in the cluster", and proving it fires would otherwise mean deploying a broken release to
# prove a check works. Two files instead of one buys a mutation that costs nothing.
compare() {
  python3 scripts/deploy_check.py "$1" "$2"
}

RENDER_JSON=$(render | python3 -c '
import json, sys
# helm speaks YAML; the subset it emits for a manifest is not JSON, so the documents are converted
# with the yaml module when it is there and refused clearly when it is not. Refused rather than
# half-parsed: a check that silently reads fewer documents than the render has is a check that passes
# for the wrong reason.
try:
    import yaml
except ImportError:
    sys.exit("deploy-check needs PyYAML to read the render: pip install pyyaml")
docs = [d for d in yaml.safe_load_all(sys.stdin.read()) if isinstance(d, dict)]
json.dump(docs, sys.stdout)
')

compare <(printf '%s' "$RENDER_JSON") <(live)
