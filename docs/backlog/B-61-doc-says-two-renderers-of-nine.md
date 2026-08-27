---
id: B-61
title: "The design document says the client renders two of the nine components; it renders all nine"
status: open
priority: P3
size: XS
stage: stage-m4-proof
epic: feature-client
---

# B-61 — A document that was true and is not, about the one thing it is read for

`docs/design/design-app-canvas.md` says sections 02 and 03 cannot be photographed because "this
client registers a renderer for two of the nine types only (`usage_counter_card` and `esim_qr`)".
`konektRenderers` now installs all nine — plan card, order row, banner, eSIM card, snackbar, step
meter, skeleton and the bottom bar included — and the gallery photographs both sections.

The claim is load-bearing rather than decorative: it is the stated reason two sections of the canvas
have no goldens, and it reads as a live constraint on what can be verified. Somebody planning work
against it would price a renderer that exists.

- **The decision and its reason.** Fix the sentence and say what replaced it, rather than deleting
  it: the paragraph records a real constraint that was real, and the interesting half is that the
  goldens arrived without anybody updating the prose beside them. Prose next to generated artifacts
  is not checked by anything.
- **Worth one line of machinery, not more.** `KonektRendererCoverageTest` already holds the two lists
  apart; what nothing does is compare the document's number to it. A doc claim naming a count is
  exactly the kind that rots — and `code_anchors` cannot see a number.
- AC: the document states which components have renderers, and the statement is derived from
  `konektRenderers` rather than typed.
- Anchors: `docs/design/design-app-canvas.md`,
  `client/src/commonMain/kotlin/io/konekt/client/render/KonektRenderers.kt`.
