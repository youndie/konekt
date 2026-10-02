# The documentation gate. Copied from docs-bootstrap's templates/Makefile to the root of the
# repository, next to .github/workflows/check.yaml copied from templates/workflow-check.yaml.
#
#   make check    the gate and the reports - exactly what CI runs
#   make fix      regenerate the backlog index, append missing coverage-map lines
#
# ONE VERSION OF THE CHECKS, WRITTEN DOWN ONCE: the `uses: youndie/docs-bootstrap@<ref>` line in
# .github/workflows/check.yaml. CI runs the checks at that ref because the runner resolves the line.
# This file reads the same line and fetches the same ref into .docs-bootstrap/, a directory that
# ignores itself, so `make check` here runs what CI runs - the same scripts, the same guard, the same
# flags. Renovate bumps the line, and the next `make check` fetches what CI already moved to.
#
# WHY THE SCRIPTS ARE NOT COPIED IN. A copied check runs, but at the version of the day it was copied,
# and a fix upstream never arrives: across one portfolio 18 copies of backlog_index.py were found in
# three versions, eleven of them without the guard that makes `--check` fail when the backlog has
# gone missing - a guard that existed upstream the whole time.
#
# WHY THE VERSION IS NOT ALSO WRITTEN HERE. A version pinned in the workflow and again in this file is
# two pins, and two pins drift: one is bumped, the other is found months later, and "green here, red
# there" comes back with nobody able to say which side is right. So this file holds none; if the
# workflow names two different refs, it refuses to choose.
#
# WHAT LIVES HERE is what is this repository's own: where the tree is, how the backlog is kept, and
# checks of its own under `gate`. How the documents are checked - including the guard that fails the
# gate when docs/ or the backlog is not there - is in check.mk at the pinned version, and changes
# arrive with a bump instead of with a re-copy.
#
# ONLY A GOAL THAT RUNS THE CHECKS LOADS THEM. A project adds targets of its own below this head - a
# chart, a stand, a release - and make reads every included file, fetching the ones that are
# missing, before it runs any goal at all. Included unconditionally, check.mk made each of those
# targets, `make` alone and even `make -n` read the pin and download it on a fresh clone, and fail
# offline. So it is included only when a goal asked for - on the command line, or the default goal
# when there is none - is in DOCS_BOOTSTRAP_GOALS or is one of check.mk's own `docs-` targets; every
# other goal runs without docs-bootstrap and without the network. A goal of the project's own that
# leads to the checks (`ci: check build`) is added to DOCS_BOOTSTRAP_GOALS, above the line that says
# nothing below is meant to be edited; one that is not added stops on a message naming that
# variable.
#
# OVERRIDES. `DOCS_BOOTSTRAP=<dir>` runs the checks from a directory instead of the pinned ref: a
# clone of docs-bootstrap you are changing, or - offline, or without GitHub Actions - a committed
# copy of its check.mk, scripts/ and .claude-plugin/. That last one is the copy route again, with its
# drift; it is the fallback, not the default.

DOCS ?= docs
BACKLOG ?= backlog.md
# How the backlog is kept (docs-bootstrap SKILL.md, step 7): `files` - one file per item in
# $(DOCS)/backlog/ and the generated index in $(BACKLOG); `milestones` - one hand-kept file at
# $(BACKLOG), usually BACKLOG.md; `none` - no backlog, yet.
BACKLOG_FORM ?= files
# A directory whose subdirectories are the repositories the code anchors point into. `..` is the
# directory this clone sits in - in CI, a directory holding this clone and nothing else; on a laptop,
# its siblings too, which a suffix match can mistake for this repository.
REPOS ?= ..
PY ?= python3

# Where the pin is, and what it names.
DOCS_BOOTSTRAP_PIN ?= .github/workflows/check.yaml
DOCS_BOOTSTRAP_REPO ?= youndie/docs-bootstrap
DOCS_BOOTSTRAP_CACHE ?= .docs-bootstrap
# The revision of this file. check.mk says so when a newer docs-bootstrap expects a newer one.
DOCS_BOOTSTRAP_SHIM := 2

# The goals that load the checks - and so read the pin and, on a fresh clone, fetch it. check.mk's
# `docs-` targets load them by themselves. A goal of this repository's own that runs one of these
# goes here too, e.g. for `ci: check build`:
#	DOCS_BOOTSTRAP_GOALS += ci
DOCS_BOOTSTRAP_GOALS := check gate report fix

.DEFAULT_GOAL := help
.PHONY: help check gate report fix

help:
	@echo "make check   - the gate and the reports: exactly what CI runs"
	@echo "make gate    - the blocking half alone"
	@echo "make report  - non-blocking: BDD coverage, code anchors, upstream issue states"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. docs-bootstrap's gate first - the guard, the backlog index, the cross-references, the
# coverage map - then this repository's own. Any of them failing means the documentation is
# internally inconsistent, which is a defect in the documentation and not a matter of opinion.
#
# EVERY ONE OF THEM WAS PROVED BY MUTATION on 2026-09-04, because a guard is only worth its line in
# this file if it fails when it should: a title changed in the index and a status changed under it
# (`backlog_index`), a dead internal link (`docs_check`), a map entry deleted (`coverage_map`), a
# sentence saying a done item "is not built yet" (`stale_citations`), a value added to the chart
# without moving its version (`chart_version`), a service document put back to the version before
# the bump (`stated_versions`). All six refused. So did `code_anchors` and `upstream_state` among
# the reports.
#
# `bdd_report` did not, and that was `B-120`: a named test replaced with one that cannot exist
# changed its output by nothing, because a first token naming a MODULE rather than a sibling
# repository meant "not checked" and the summary counted it as covered anyway. The fix was made in
# this repository's copy and went upstream in docs-bootstrap 0.3.1, with the skip of
# `status: deprecated` documents in `code_anchors`; the copies are gone since.
gate: docs-gate
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

# Non-blocking, on purpose. docs-bootstrap's two reports - bdd_report counts scenarios, and
# demanding a percentage is meaningless while acceptance is done by hand; code_anchors goes stale
# because of a refactor in somebody else's repository rather than because of an edit here - and
# upstream_state, which asks those repositories what state their issues are actually in. That needs
# the network and an authenticated `gh`, and a gate that fails on a flight is a gate somebody turns
# off: without either it says so and exits 0. All three are read by a person.
report: docs-report
	$(PY) scripts/upstream_state.py --docs $(DOCS)

fix: docs-fix

# -- where the checks come from. Nothing below is meant to be edited. ------------------------------

# The goals this run was asked for: the command line's, or the default goal when it names none.
DOCS_BOOTSTRAP_ASKED := $(or $(MAKECMDGOALS),$(.DEFAULT_GOAL))

ifneq ($(filter $(DOCS_BOOTSTRAP_GOALS) docs-%,$(DOCS_BOOTSTRAP_ASKED)),)

ifndef DOCS_BOOTSTRAP
DOCS_BOOTSTRAP_REF := $(sort $(shell sed -n -E 's|^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*"?$(DOCS_BOOTSTRAP_REPO)@([^"[:space:]]+).*|\2|p' $(DOCS_BOOTSTRAP_PIN) 2>/dev/null))
ifeq ($(words $(DOCS_BOOTSTRAP_REF)),0)
$(error no `uses: $(DOCS_BOOTSTRAP_REPO)@<ref>` in $(DOCS_BOOTSTRAP_PIN). That line is the version of the checks, for CI and for this file alike - copy templates/workflow-check.yaml, or run with DOCS_BOOTSTRAP=<a local copy>)
endif
ifneq ($(words $(DOCS_BOOTSTRAP_REF)),1)
$(error $(DOCS_BOOTSTRAP_PIN) pins $(DOCS_BOOTSTRAP_REPO) at more than one ref: $(DOCS_BOOTSTRAP_REF). One version of the checks, one ref - make every uses: line name the same one)
endif
DOCS_BOOTSTRAP := $(DOCS_BOOTSTRAP_CACHE)/$(DOCS_BOOTSTRAP_REF)
else ifeq ($(wildcard $(DOCS_BOOTSTRAP)/check.mk),)
$(error DOCS_BOOTSTRAP=$(DOCS_BOOTSTRAP) holds no check.mk)
endif

include $(DOCS_BOOTSTRAP)/check.mk

else

# Not loaded, so no `docs-` target exists in this run. A goal that reaches one anyway is missing from
# DOCS_BOOTSTRAP_GOALS, and make's own "No rule to make target" would not say so.
docs-%:
	@echo "$@ is a target of docs-bootstrap's check.mk, which this run did not load: '$(DOCS_BOOTSTRAP_ASKED)' is not in DOCS_BOOTSTRAP_GOALS ($(strip $(DOCS_BOOTSTRAP_GOALS))). Add the goal that leads to $@ to DOCS_BOOTSTRAP_GOALS in the Makefile." >&2; exit 2

endif

# The fetch. A tarball of the ref rather than a clone: a tag, a branch and a commit SHA (what
# Renovate writes when it pins digests) are all one URL, and no history is needed. Unpacked next to
# its final place and moved in only once complete, so an interrupted fetch never leaves a directory
# that looks like a version. GNU make 3.81 - the one macOS ships - announces the missing file
# ("check.mk: No such file or directory") just before fetching it; that line is not the error.
$(DOCS_BOOTSTRAP_CACHE)/%/check.mk:
	@echo "docs-bootstrap: fetching $(DOCS_BOOTSTRAP_REPO)@$* - the ref $(DOCS_BOOTSTRAP_PIN) pins"
	@rm -rf "$(@D).part" && mkdir -p "$(@D).part"
	@curl -fsSL --retry 2 -o "$(@D).part/src.tar.gz" "https://codeload.github.com/$(DOCS_BOOTSTRAP_REPO)/tar.gz/$*" || { rm -rf "$(@D).part"; echo "could not fetch $(DOCS_BOOTSTRAP_REPO)@$* - offline, or a ref that does not exist? DOCS_BOOTSTRAP=<dir> runs a local copy instead" >&2; exit 1; }
	@tar -xzf "$(@D).part/src.tar.gz" -C "$(@D).part" --strip-components=1 && rm -f "$(@D).part/src.tar.gz"
	@test -f "$(@D).part/check.mk" || { echo "$(DOCS_BOOTSTRAP_REPO)@$* has no check.mk - versions before 0.3.0 cannot be pinned this way" >&2; rm -rf "$(@D).part"; exit 1; }
	@rm -rf "$(@D)" && mv "$(@D).part" "$(@D)"
	@echo '*' > "$(DOCS_BOOTSTRAP_CACHE)/.gitignore"

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
	docker build -f deploy/Dockerfile -t konekt-server:release .
	# The AOT cache, trained inside the image just built and verified there — the same step the
	# publish workflow runs, so the image this produces is the shape of the one that ships (B-123).
	scripts/aot-image.sh konekt-server:release ghcr.io/youndie/konekt-server:$(VERSION)
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
