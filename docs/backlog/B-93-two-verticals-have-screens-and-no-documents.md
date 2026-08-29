---
id: B-93
title: "The tariff change and the custom package builder have screens and no documents in any layer"
status: open
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
