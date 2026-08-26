#!/usr/bin/env bash
# THE ROLLING-DEPLOY CHECK: the PREVIOUS release's server against the CURRENT schema.
#
# It is the one thing that can falsify B-36's claim. Expand-and-contract says a migration must leave
# the running version working, because during a rolling deploy the new schema and the old code are
# live at the same time — and every test in this repository runs the new code against the new schema,
# which is the one combination that cannot fail.
#
# WHAT "PREVIOUS RELEASE" MEANS HERE. Nothing has been tagged yet, and B-35 recorded that as the
# reason this was pending — with the added argument that two commits would produce identical schemas.
# That stopped being true when `V10__roaming_package.sql` landed: any commit before it has a server
# built against V9 and this stand runs V10. So the check is real now, with a commit standing in for a
# tag, and it becomes the tag's business the day there is one.
#
# Usage:
#   scripts/rolling-check.sh 8c8c958          # an explicit ref
#   scripts/rolling-check.sh                  # the newest tag, and it refuses if there is none
set -euo pipefail

cd "$(dirname "$0")/.."

PREVIOUS="${1:-}"
if [ -z "$PREVIOUS" ]; then
  PREVIOUS=$(git describe --tags --abbrev=0 2>/dev/null || true)
fi
if [ -z "$PREVIOUS" ]; then
  cat >&2 <<'MSG'
No release has been tagged, so there is no previous release to run.

Pass a ref to check against a commit instead:

    scripts/rolling-check.sh <ref>

A commit is a fair stand-in while the schema has moved since it — which is the only case where this
check can say anything. A ref whose migrations are identical to HEAD's proves nothing and this script
refuses it below.
MSG
  exit 2
fi

PREVIOUS_SHA=$(git rev-parse --short "$PREVIOUS")
echo "rolling-check: previous = $PREVIOUS ($PREVIOUS_SHA)"

# THE CHECK IS VACUOUS IF THE SCHEMA HAS NOT MOVED, and a vacuous green here is worse than no check:
# it is a claim about rolling deploys backed by running the same code against the same schema.
MIGRATIONS=shared/db/src/main/resources/db/migration
if git diff --quiet "$PREVIOUS_SHA" HEAD -- "$MIGRATIONS"; then
  echo "rolling-check: no migration changed between $PREVIOUS_SHA and HEAD — this check would prove nothing" >&2
  exit 2
fi
echo "rolling-check: migrations added since $PREVIOUS_SHA:"
git diff --name-only --diff-filter=A "$PREVIOUS_SHA" HEAD -- "$MIGRATIONS" | sed 's/^/  /'

# The previous tree, inside the repository because the build machine sees this directory and nothing
# else. `git archive` rather than a worktree: it produces a clean tree with no git metadata to keep in
# step, and the build that runs on it is the ordinary one.
rm -rf .rolling/previous
mkdir -p .rolling/previous
git archive "$PREVIOUS_SHA" | tar -x -C .rolling/previous

# EVERY BUILD AND EVERY CONTAINER RUNS ON THE BUILD MACHINE, which is where Docker and the JDK are;
# only git runs here. `wsl-run` maps the current directory to its counterpart there, which is why the
# extracted tree had to go inside the repository rather than into /tmp.
WSL=~/.claude/bin/wsl-run

echo "rolling-check: building the $PREVIOUS_SHA server"
(cd .rolling/previous && "$WSL" ./gradlew :server:installDist -q)

# FROM AN EMPTY DATABASE, ALWAYS. A schema is cumulative and a stand is not torn down between runs,
# so a previous run's migrations are still applied — including one somebody added to see this check
# fail. Removing the file does not un-rename a column, and the next run then measures whatever the
# last one left rather than what the tree says. Proved by doing exactly that: the check stayed red
# after the breaking migration was deleted.
echo "rolling-check: tearing the stand down so the schema is the tree's and not the last run's"
"$WSL" 'make stand-down' >/dev/null 2>&1 || true

echo "rolling-check: bringing the stand up on the CURRENT schema"
"$WSL" 'make stand-up' >/dev/null

echo "rolling-check: starting the $PREVIOUS_SHA server against it"
"$WSL" 'docker compose -f deploy/compose.yaml -f deploy/compose.rolling.yaml up -d --build --wait server-previous'

echo "rolling-check: driving the previous server"
"$WSL" './gradlew :e2e:rollingCheck'
