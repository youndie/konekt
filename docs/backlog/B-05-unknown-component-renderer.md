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
- **The reporting half is now the toolkit's**, since kompot#81 landed in `0.31.0.74`: konekt provides
  a `KompotDegradationSink` into tracy (with `originalType` as an indexed field) and a katcher
  breadcrumb, rather than writing reporting into its own renderer. That buys two kinds konekt had not
  thought to ask for — `UNRENDERABLE_COMPONENT`, a type that decodes with no renderer registered, and
  `UNKNOWN_ACTION`, a tap that reaches the application and can do nothing.
- The rejected alternative is leaving the default and relying on the server always sending a
  `fallback`. It works until the day it is forgotten, and the symptom is a hole.
- Not covered: nothing, now. `UnknownAction` was the gap here and the sink covers it.

- AC: a screen containing a type absent from the registry draws the placeholder in both densities and
  everything around it still renders.
- AC: the same render produces one tracy record carrying the unknown type name, and an unknown
  *action* produces one too — reported once, not once per level of the tree.
- Anchors: `client/src/commonMain/kotlin/io/konekt/render/UnknownBlockRenderer.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.4.
