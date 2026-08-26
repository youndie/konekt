# One gate, and CI runs exactly this target.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal state
# of affairs, and people stop reading either. So: whatever is not in `make check` is not a gate, and
# whatever is in it runs the same way in both places.
#
# Every script defaults to `docs` in the working directory, so the variables below exist to be
# overridden rather than because anything needs them.

DOCS ?= docs
BACKLOG ?= backlog.md
REPOS ?= ..
PY ?= python3

.PHONY: check gate report fix help

help:
	@echo "make check   - the gate: blocking checks, exactly what CI runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation is internally inconsistent, which is a
# defect in the documentation and not a matter of opinion.
gate:
	$(PY) scripts/backlog_index.py --check --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/docs_check.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --check --docs $(DOCS)

# Non-blocking, on purpose.
#
# bdd_report counts scenarios; demanding a percentage is meaningless while acceptance is done by
# hand. code_anchors goes stale because of a refactor in somebody else's repository rather than
# because of an edit here, and while there is no code in this one it reports every anchor as
# missing — which is correct and is why it does not block. Both are read by a person.
report:
	$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)

fix:
	$(PY) scripts/backlog_index.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)

# ── the OpenAPI document ────────────────────────────────────────────────────────────────────────
#
# `docs/api/openapi.json` is a build artefact: the conformance kit reads endpoint kinds out of it and
# assumes no addresses, so without it there is no walk at all. It is GENERATED from the routing tree
# and committed, and every build compares the two — a hand-edit fails the build until the next
# recording overwrites it.
#
# ON THE MAC, and that is not a preference. This repository is a one-way mutagen replica: a file
# written on the Linux box is reverted on the next sync, so a recording there looks like it did
# nothing at all. `LOCAL=1` is what gets the command past the hook that otherwise sends Gradle to WSL.

.PHONY: openapi

openapi:
	LOCAL=1 ./gradlew :server:openApi

# ── the conformance gate ────────────────────────────────────────────────────────────────────────
#
# THE ASSERTION IS ON COVERAGE AND IT COMES FIRST. `kompot-tck` says it about itself: a check that
# found no matching endpoint passes silently, which is the commonest way to end up with a
# conformance kit that proves nothing. `check(report.isClean)` — the readme's example — is green on
# a server whose screens the walk never reached, so the gate asks what would be visited, per check
# and per endpoint, before anything reads a verdict.
#
# The same command CI runs, as its own step. It needs no stand: the subject is the committed
# `docs/api/openapi.json`, which is the file the kit is handed as `TckConfig.openApi`.

.PHONY: tck

tck:
	./gradlew :server:test --tests 'io.konekt.conformance.*'

# ── the end-to-end stand ────────────────────────────────────────────────────────────────────────
#
# One command, the same one locally and in CI. A stand only CI knows how to start is a stand nobody
# debugs, and the failures worth catching here are the ones that only appear between processes.

COMPOSE := docker compose -f deploy/compose.yaml

.PHONY: stand-up stand-down stand-logs e2e rolling-check release-image

# The distribution is built OUTSIDE the image — see deploy/Dockerfile for why — so it has to exist
# before the image does. Forgetting that step gives a container running whatever was built last time,
# which is the most confusing failure this stand can produce.
stand-up:
	./gradlew :server:installDist
	$(COMPOSE) up -d --build --wait
	# An application and a key in katcher, without which every crash report is refused. A separate
	# step rather than a service `up` starts: it exits when it is done, and a one-shot container is
	# an error to `--wait` unless something depends on it — which here could only be the server, and
	# the server deliberately depends on none of the observability trio.
	$(COMPOSE) --profile seed run --rm katcher-seed

stand-down:
	# `--profile seed`, and leaving it out cost an hour of measuring a stand that was not clean.
	# `down -v` removes the volumes of ACTIVE services only, and `katcher-data` is also referenced by
	# `katcher-seed`, which lives in a profile `down` does not consider — so katcher's database
	# survived every teardown. Every "fresh stand" reading after that was the previous run's data,
	# which is the shape of mistake that makes a green test mean nothing.
	$(COMPOSE) --profile seed down -v

stand-logs:
	$(COMPOSE) logs --tail=200

# Needs a stand already up. Deliberately not part of `check`: wired in, it would fail every ordinary
# build on a machine that has not started one, and a suite that fails for reasons unrelated to the
# change is a suite people learn to ignore.
e2e:
	./gradlew :e2e:e2e :client:standTest

# THE RELEASE IMAGE, tagged with the version rather than with the day.
#
# Built here rather than in CI because CI has no credential for the registry either — see `B-47`. What
# this target produces is a LOCAL tag that the stand can be pointed at, which proves everything about
# running an artefact except the registry round trip.
#
#     make release-image                 # tags the newest tag's build
#     make release-image VERSION=v0.1.0
VERSION ?= $(shell git describe --tags --abbrev=0 2>/dev/null)
release-image:
	@test -n "$(VERSION)" || { \
		echo "no tag to name the image after."; \
		echo "On the build machine there is no git checkout, so the default cannot be read:"; \
		echo "    make release-image VERSION=\$$(git describe --tags --abbrev=0)"; \
		exit 2; }
	./gradlew :server:installDist
	docker build -f deploy/Dockerfile -t ghcr.io/youndie/konekt-server:$(VERSION) .
	@echo "built ghcr.io/youndie/konekt-server:$(VERSION) — pushing it needs write:packages"

# THE ROLLING-DEPLOY CHECK: the previous release's server against the current schema.
#
# Deliberately not part of `e2e` and not part of `check`. It tears the stand down and rebuilds an old
# server, which is minutes rather than seconds, and it is the one check whose subject is a PAIR of
# versions rather than this one — so it belongs with a release, not with every commit.
#
#     make rolling-check                 # against the newest tag; refuses if nothing is tagged
#     make rolling-check PREVIOUS=<ref>  # against a commit, while there are no tags
rolling-check:
	scripts/rolling-check.sh $(PREVIOUS)
