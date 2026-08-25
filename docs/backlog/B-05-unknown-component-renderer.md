---
id: B-05
title: "An unknown component draws a block and reports itself"
status: open
priority: P1
size: S
stage: stage-m0-wire
blocked_by: [B-03]
---

# B-05 — An unknown component draws a block and reports itself

The toolkit's `UnknownComponentRenderer` calls `println` and returns when the server named no
fallback — nothing is drawn (research §1.4). The canvas is explicit that this must never be a hole:
a full card when the block is the screen's subject, one line among known rows, *"never a blank gap"*.

- **The decision and its reason.** Replace the registry entry for `UnknownComponent::class`. The
  registry is a plain map and this is a supported extension point, so it costs one entry and no fork.
  The event goes to tracy with the `originalType` as an indexed field and to a katcher breadcrumb,
  because the situation this exists for is a newer server against an older client in the field, where
  nobody is holding a console.
- The rejected alternative is leaving the default and relying on the server always sending a
  `fallback`. It works until the day it is forgotten, and the symptom is a hole.
- Not covered: `UnknownAction`, which the toolkit does not surface at all — raised as
  [U2](../research/research-upstream-proposals.md#u2).

- AC: a screen containing a type absent from the registry draws the placeholder in both densities and
  everything around it still renders.
- AC: the same render produces one tracy record carrying the unknown type name.
- Anchors: `client/src/commonMain/kotlin/io/konekt/render/UnknownBlockRenderer.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.4.
