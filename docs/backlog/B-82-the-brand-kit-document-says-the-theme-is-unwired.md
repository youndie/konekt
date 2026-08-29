---
id: B-82
title: "The brand-kit document says no theme is served over HTTP, and the server has been serving one since B-22"
status: open
priority: P2
size: XS
stage: stage-m6-reframe
---

# B-82 — A paragraph that expired inside the item that wrote it

`docs/design/design-brand-kit.md:186-190` states:

> **As of B-22 the composition root does not call it yet**, and the endpoint's path constant does
> not exist … nothing is served over HTTP.

Three things contradict it, all in the tree today:

- `feature/theme-shared-api/.../BrandTheme.kt` declares `PATH = "/api/v1/theme"` — the constant the
  paragraph says does not exist, in the `*-shared-api` module the paragraph says has to be created;
- `server/src/main/kotlin/io/konekt/Application.kt` mounts it in a `PUBLIC` route group;
- `client/.../app/KonektScreenSource.kt` fetches it, and `KonektApp` applies it.

[B-22](B-22-brand-b.md) itself records the HTTP half as the last thing it did. So the document
disagrees with the item it cites, in the direction that understates the build — which is the rarer
and more confusing direction: a reader who trusts it concludes the white-label claim is unproven,
and the one thing this repository does prove about white-labelling is exactly that half.

- **The decision: delete the paragraph and replace it with what is served, from the route table.**
  Not "update the wording" — the paragraph is about a state that no longer exists, and editing it in
  place is how a document acquires two tenses.
- **Why it happened is worth one line in the document, because it will happen again**: the paragraph
  was written mid-item and was true for the length of one commit. A handoff note inside a design
  document has no owner after the item closes.
- The rejected alternative is a periodic re-read of the design documents. `code_anchors.py` cannot
  catch this class at all — every path in the paragraph is valid; it is the claim about them that is
  false.
- This item does **not** touch the typography sentence at line 36-38, which is correct and is
  [B-83](B-83-typography-does-not-ship-from-the-server.md)'s subject in the other document.

- AC: `design-brand-kit.md` describes the theme endpoint as served, naming the resource class, the
  route group and the client call, and no sentence in the file describes a future state.
- Anchors: `docs/design/design-brand-kit.md`,
  `feature/theme-shared-api/src/commonMain/kotlin/io/konekt/feature/theme/shared/api/BrandTheme.kt`,
  `server/src/main/kotlin/io/konekt/theme/ThemeRoutes.kt`, `server/src/main/kotlin/io/konekt/Application.kt`.
