---
id: B-93
title: "The tariff change and the custom package builder have screens and no documents in any layer"
status: done
priority: P2
size: S
stage: stage-m7-completeness
blocked_by: [B-86, B-87]
---

# B-93 — Two verticals the documentation does not know exist

`docs/features/` holds five documents and `docs/screens/` four. Neither directory has anything for the
tariff change or for the custom package builder, and both now have screens a subscriber reaches:
[B-86](B-86-changing-tariff-has-no-screen.md) and
[B-87](B-87-the-custom-package-cannot-be-bought.md) gave them the way in that they lacked.

This was defensible while they were server-only — `B-39` filled the layers *from the code that
exists*, and a vertical with no client surface has no screen document by construction. It stops being
defensible the moment a subscriber can walk one: the coverage map in `docs/README.md` then describes
a product with four screens where there are seven, and a reader working from the documentation would
conclude two features are not built.

- **The decision: one feature document and one screen document for each, written from the code, in one
  pass over both.** Both at once rather than half of each with its item, because the two share the
  shape — a catalogue, a request, a confirmation, a result — and writing them together is what makes
  the second one cheap and the pair consistent.
- **Each carries BDD scenarios naming the tests that already exist.** `TariffScreenScenarioTest` and
  `CustomPackageScenarioTest` are acceptance criteria that run; a scenario written without the
  `**Automated:**` line beside it would understate the coverage this build actually has.
- The rejected alternative is to leave them out and note the asymmetry in the coverage map. A map that
  explains its own gaps is a map nobody trusts to be complete.

- AC: `docs/features/feature-tariff-change.md` and `docs/features/feature-custom-package.md` exist,
  each with a code-anchors table and BDD scenarios that name the tests covering them.
- AC: `docs/screens/screen-tariffs.md` and `docs/screens/screen-custom-package.md` exist, covering the
  states each screen has — including the refused and the already-pending ones.
- AC: the coverage map counts and lists all of them, and `make check` is green.
- Anchors: `docs/README.md`, `docs/features/`, `docs/screens/`,
  `server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt`,
  `server/src/main/kotlin/io/konekt/packages/`.

## What was done

Four documents, written from the code in one pass over both verticals — which is what made the second
one cheap and the pair consistent, and why this was one item rather than half of each inside `B-86`
and `B-87`.

| | |
|---|---|
| [feature-tariff-change](../features/feature-tariff-change.md) | the second saga, its billing boundary, and the six rules that make "both tariffs are true until the date" work |
| [feature-custom-package](../features/feature-custom-package.md) | three quantities, a price the server computes on every change, and a plan the catalogue did not write down |
| [screen-tariffs](../screens/screen-tariffs.md) | the catalogue and one change, with **every** state of each — including the already-pending one and the four ends of a change |
| [screen-custom-package](../screens/screen-custom-package.md) | the builder, including the state the item asked for by name: unaffordable, where the submit is still offered |

**Nine BDD scenarios, all nine automated**, each naming a test that exists — `e2e
TariffScreenScenarioTest`, `e2e CustomPackageScenarioTest`, `server TariffScreensTest`, `server
CustomPackagePlansTest`, `server TariffChangeSagaTest`. The report moved from 60 scenarios at 91% to
69 at 92%. A scenario without an `**Automated:**` line would have understated coverage this build
already has, which is the asymmetry that line exists to make visible.

**Neither feature names a hand-written endpoint document**, and the frontmatter says why rather than
carrying an empty list: the four tariff routes and the three package routes are in the generated
`api-openapi`, which is derived from the routing tree and cannot be wrong about a path, a method or an
auth tier. What it cannot say is *why*, and that is what the feature documents are for. A second
hand-written description of the same routes is how a document starts disagreeing with the server.

## Verified

`make check` green: `docs_check` reports 28 documents and no errors, and the coverage map matches the
files — `Features (5)` → `(7)`, `Screens / flows (4)` → `(6)`.

## What is deliberately not in scope

An `endpoint-tariff.md` and an `endpoint-packages.md`. Both would be worth having and neither is this
item: the ACs asked for the feature and screen layers, and the API layer's own question — which of
seven verticals deserves a hand-written endpoint document — is a decision about that layer rather than
about these two features.

## Anchors

| What | Where |
|---|---|
| The four documents | `docs/features/feature-tariff-change.md`, `docs/features/feature-custom-package.md`, `docs/screens/screen-tariffs.md`, `docs/screens/screen-custom-package.md` |
| The map that counts them | `docs/README.md` |
| What they were written from | `server/src/main/kotlin/io/konekt/tariff/`, `server/src/main/kotlin/io/konekt/packages/` |
