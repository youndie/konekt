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

# ── the end-to-end stand ────────────────────────────────────────────────────────────────────────
#
# One command, the same one locally and in CI. A stand only CI knows how to start is a stand nobody
# debugs, and the failures worth catching here are the ones that only appear between processes.

COMPOSE := docker compose -f deploy/compose.yaml

.PHONY: stand-up stand-down stand-logs e2e

# The distribution is built OUTSIDE the image — see deploy/Dockerfile for why — so it has to exist
# before the image does. Forgetting that step gives a container running whatever was built last time,
# which is the most confusing failure this stand can produce.
stand-up:
	./gradlew :server:installDist
	$(COMPOSE) up -d --build --wait

stand-down:
	$(COMPOSE) down -v

stand-logs:
	$(COMPOSE) logs --tail=200

# Needs a stand already up. Deliberately not part of `check`: wired in, it would fail every ordinary
# build on a machine that has not started one, and a suite that fails for reasons unrelated to the
# change is a suite people learn to ignore.
e2e:
	./gradlew :e2e:e2e
