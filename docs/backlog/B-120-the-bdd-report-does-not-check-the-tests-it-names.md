---
id: B-120
title: "The BDD report says 92% automated and verifies almost none of it: a test in a module it cannot resolve is silently not checked"
status: open
priority: P2
size: S
stage: stage-m7-completeness
---

# B-120 — 61 of 66 automated, and the tests were not looked for

`scripts/bdd_report.py` prints a coverage figure and, given `--repos`, promises more: *"plus a check
that the named tests exist"*. `make report` gives it `--repos ..`. The figure it prints is

```
TOTAL                                     66        61   (92%)

A test is named that the repository does not contain:
  feature-roaming:  feature/roaming-server-data
  feature-roaming:  feature/roaming-server-data
  feature-roaming:  feature/roaming-server-data
```

Both halves of that output are wrong, in opposite directions, and one experiment shows it: replace
`` `e2e RoamingScenarioTest` `` in `feature-roaming.md` with `` `e2e ThisTestDoesNotExistAtAll` `` and
**the report does not change**. A test that cannot exist is reported as automated.

## The two mechanisms

**A repository the tool cannot find means "nothing was checked", and nothing says so per line.**
`verify()` builds one candidate, `<repos>/<repo>`, and when it is not a directory sets
`found = None` — the honest value, and its comment says as much. `missing` then collects only
`found is False`. konekt is one repository of many modules, so `e2e`, `server` and `client` — the
first token on eleven of the fourteen repository-qualified lines — resolve to no directory at all
and are never searched. The percentage counts them regardless.

**A second entry on one line loses its test name.** The roaming scenarios write

    **Automated:** `e2e RoamingScenarioTest`, `feature/roaming-server-data RoamingPackageTest`

and the parser yields `repo='e2e' test='RoamingScenarioTest'` for the first and
`repo='' test='feature/roaming-server-data'` for the second — the test name dropped. With no
repository it searches everywhere for the literal `feature/roaming-server-data`, fails, and prints
the module path in the column where a test name belongs. `RoamingPackageTest` exists, at
`feature/roaming-server-data/src/test/kotlin/io/konekt/feature/roaming/server/data/RoamingPackageTest.kt`.

So the only complaint the report makes is about a test that is there, while the tests it cannot see
are counted as covered — which is the shape this repository keeps finding: a green number that
never asked the question (`B-117`'s soak, `B-119`'s rolling check).

## What it does not mean

The scenarios are not fabricated, and this was settled rather than assumed: every backticked entry
on every `**Automated:**` line in `docs/features/` — the bulleted majority as well as the fourteen
repository-qualified ones — yields **35 distinct test names, and `git grep -w` finds all 35 in the
code**, markdown excluded so a document cannot vouch for itself. So the coverage this repository
claims is real today.

The defect is that **nothing would notice if it stopped being real** — the class of rot
`code_anchors` and `stale_citations` exist to prevent, in the one report that claims coverage. That
is why this is a P2 and not a P1: no figure in the documentation is currently false.

## Ways out

- **Resolve a module inside the repository, not only a sibling of it.** A first token that is not a
  directory under `--repos` should be tried as a path inside each candidate repository before being
  given up on; `e2e`, `server`, `client` and `feature/roaming-server-data` all resolve that way.
- **Split entries before splitting repository from test**, so a comma-separated line yields two
  well-formed entries rather than one and a fragment.
- **Say the unchecked ones out loud.** `found is None` is currently indistinguishable from a pass in
  the summary; a line saying "N named tests were not looked for" is what would have made this
  visible a month ago.
- The tool is not konekt's — it comes from the documentation format's own kit — so whichever fix is
  taken should go upstream rather than only here. Ask before filing anything against a repository
  that is not ours.

## Acceptance criteria

- AC: renaming a test named by an `**Automated:**` line makes `make report` say so.
- AC: `feature-roaming`'s three complaints are gone because the tests are found, not because the
  check was loosened.
- AC: the summary distinguishes automated-and-verified from automated-and-not-looked-for.

## Anchors

| What | Where |
|---|---|
| The report | `scripts/bdd_report.py` (`verify`, `find_test`, the `AUTOMATED` pattern) |
| The lines it mis-parses | `docs/features/feature-roaming.md` |
| The test it says is missing | `feature/roaming-server-data/src/test/kotlin/io/konekt/feature/roaming/server/data/RoamingPackageTest.kt` |
| Where it is invoked | `Makefile` (`report`) |
