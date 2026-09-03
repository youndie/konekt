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
	# A DOCUMENT DESCRIBING FINISHED WORK AS STILL TO DO. Blocking, and it belongs here rather
	# than among the reports: unlike a rotten anchor, this cannot be caused by a rename in
	# somebody else's repository — every instance is a claim made in this tree about this tree.
	$(PY) scripts/stale_citations.py --docs $(DOCS)
	# THE CHART'S SHAPE AGAINST THE CHART'S VERSION. Blocking, and it reads git rather than the
	# tree: the question is whether the shape moved since the number did, which no snapshot of
	# the files can answer.
	$(PY) scripts/chart_version.py
	# A SERVICE DOCUMENT STATING A VERSION THE CATALOGUE NO LONGER PINS. Blocking for the same
	# reason as the two above: a bump moves `libs.versions.toml` and leaves the two lines a reader
	# trusts for "what this deployment is" behind, and no build reads prose. Only those two lines —
	# every other version in these documents is history and is supposed to stay put.
	$(PY) scripts/stated_versions.py --docs $(DOCS)

# Non-blocking, on purpose.
#
# bdd_report counts scenarios; demanding a percentage is meaningless while acceptance is done by
# hand. code_anchors goes stale because of a refactor in somebody else's repository rather than
# because of an edit here, and while there is no code in this one it reports every anchor as
# missing — which is correct and is why it does not block. upstream_state asks those repositories
# what state their issues are actually in, which needs the network and an authenticated `gh` —
# a gate that fails on a flight is a gate somebody turns off. All three are read by a person.
report:
	$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/upstream_state.py --docs $(DOCS)

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

# ── the chart ───────────────────────────────────────────────────────────────────────────────────
#
# The chart refuses a render five ways and nothing ran any of them until `B-91`: every one of those
# guards fired for the first time in front of whoever was deploying. `scripts/chart-check.sh` renders
# the valid configuration and every refused one, and checks each refusal names ITS OWN reason — a
# template broken by a typo would otherwise satisfy every negative case.

.PHONY: chart

chart:
	./scripts/chart-check.sh

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

.PHONY: stand-up stand-down stand-logs e2e rolling-check release-image deploy deploy-check

# The distribution is built OUTSIDE the image — see deploy/Dockerfile for why — so it has to exist
# before the image does. Forgetting that step gives a container running whatever was built last time,
# which is the most confusing failure this stand can produce.
# A STAND ON AN ARTEFACT WHEN ONE IS NAMED. `SERVER_IMAGE` names a published image, and then
# nothing in this tree is built at all — not the distribution, not the image — so a mistake here
# cannot make a run against a release pass. Unset, the ordinary path is unchanged.
#
#     SERVER_IMAGE=ghcr.io/youndie/konekt-server:v0.1.0 make stand-up e2e
stand-up:
ifeq ($(strip $(SERVER_IMAGE)),)
	./gradlew :server:installDist
	$(COMPOSE) up -d --build --wait
else
	@echo "running $(SERVER_IMAGE) — nothing in this tree is built"
	$(COMPOSE) up -d --no-build --wait
endif
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

# THE UPGRADE FLAG, WRITTEN DOWN WHERE IT CANNOT BE FORGOTTEN — which is the whole of this target.
#
# `--reset-then-reuse-values` and NOT `--reuse-values`. The second reuses the previous release's
# user-supplied config INSTEAD of coalescing with the new chart's `values.yaml`, so every key the
# chart has gained since the last deploy renders EMPTY. `B-106` is that, in production: three broker
# settings declared in `values.yaml`, absent from the containers, retention silently off, and
# `chart-check.sh` green throughout because it renders the chart rather than the deployment.
#
# What the right flag does NOT do is worth knowing too: it re-applies the operator's own values, so a
# value they set and the chart has since REMOVED is still carried forward. Helm has no flag that
# both takes the chart's defaults and drops an obsolete override; that one is read by a person.
#
# The check is part of the target rather than a thing to remember afterwards. A guard that has to be
# invoked separately is a guard that runs on the deploys nobody was worried about.
#
#     make deploy                        # the newest tag, like release-image
#     make deploy VERSION=v0.1.26
#     make deploy VERSION=v0.1.26 NAMESPACE=konekt RELEASE=konekt
#
# `VERSION` IS THE ONE `release-image` ALREADY DEFINES — the newest tag — and this target does not
# add a refusal of its own for the empty case. The first draft of it did, and the refusal could
# never fire: `VERSION ?=` above had always already resolved it, so the branch was unreachable code
# with an error message in it. What DOES refuse an empty version is the chart, at render time
# (`server.version is required`), and `chart-check.sh` proves that refusal names its own reason.
#
# Where the cluster is, which context reaches it and what the values are: not here, and deliberately
# — the chart carries the SHAPE and an operator keeps their own addresses and keys beside their
# cluster. This target adds no opinion about somebody else's network.
NAMESPACE ?= konekt
RELEASE ?= konekt
deploy:
	helm upgrade $(RELEASE) charts/konekt --namespace $(NAMESPACE) \
		--reset-then-reuse-values --set server.version=$(VERSION) --wait --timeout 5m
	$(MAKE) deploy-check

# Runnable on its own, because the question "is the cluster running what this chart says" is worth
# asking without deploying anything.
deploy-check:
	NAMESPACE=$(NAMESPACE) RELEASE=$(RELEASE) ./scripts/deploy-check.sh

# THE RELEASE IMAGE, tagged with the version rather than with the day.
#
# A LOCAL tag, and publishing is deliberately not part of it. The push lives in
# `.github/workflows/publish-image.yaml`, because the right to write to the registry lives in CI and
# not on a laptop — `B-47`. What this target is for is producing the same image outside CI, to point
# a stand at without waiting for a tag.
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
	@echo "built ghcr.io/youndie/konekt-server:$(VERSION) — publishing it is a tag, not a push:"
	@echo "    git tag -a $(VERSION) && git push origin $(VERSION)"

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
