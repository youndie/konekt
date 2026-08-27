---
id: B-61
title: "The design document says the client renders two of the nine components; it renders all nine"
status: done
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

## What landed

The paragraph names the eleven types instead of counting them, and
`RendererCoverageIsDocumentedTest` fails when it and `konektRenderers` disagree. **Names rather than a
number**, because a count goes stale the same way — one release later and one digit at a time — while
a list can be compared.

The sentence it replaced is kept above the correction rather than deleted: it was true when written,
stopped being true when `B-45` shipped six renderers, and the goldens for both sections arrived
**without anybody touching the prose beside them**. That is the finding, and deleting the evidence
would delete it too.

## The mutation passed, and that was the second finding

Editing the sentence and re-running the suite reported BUILD SUCCESSFUL. The test had not run:
`docs/design/design-app-canvas.md` is not an input of `:client:jvmTest`, so Gradle saw no reason to
re-execute and the previous run's XML said everything passed.

This repository already knew: `AppleTestsAreNotClaimedTest` carries a comment saying a guard reading a
file outside the module is not a Gradle input and needs `--rerun-tasks` to be believed — **and has no
input declaration of its own.** So the warning was written down and the fix was not applied, which
means that guard is trustworthy only when somebody remembers the comment.

Declared now, beside the goldens, which were added to the same task for the same near-miss. The
mutation fails properly: it names both sets and which type is missing.

- Left open deliberately: `AppleTestsAreNotClaimedTest`'s own declaration. It reads
  `.github/workflows/`, which is a different file set, and folding it into this change would make two
  fixes share one commit.
