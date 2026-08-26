---
id: B-44
title: "A component that decodes and cannot be drawn is invisible from every guard"
status: open
priority: P1
size: S
stage: stage-m4-proof
epic: feature-client
---

# B-44 — A component that decodes and cannot be drawn is invisible from every guard

This build's forward-compatibility argument covers exactly one of the two ways a client can fail to
render something, and until today nobody had noticed there were two.

**Cannot DECODE** — a type the client has never heard of. `UnknownComponent`, the degradation block,
the sink, `originalType` indexed in tracy, the count an operator reads. All of that exists and works.

**Cannot DRAW** — a type in the dictionary with no entry in the registry. It decodes into its own
class, `KompotRegistry.RenderNode` finds no renderer, and the toolkit's own fallback draws a red
"Unknown component". It is not an `UnknownComponent`, so it reaches none of the machinery above: no
block, no sink, no record, nothing to count. From an operator's side that screen is silent.

It shipped. `banner` sat in `notYetRendered` and the home screen sends one to every subscriber with no
counters — so the **first screen every subscriber sees** drew a red error, and the state was found by
running the iOS application against the stand with a fresh account. Six types are still in that list.

- **The decision and its reason.** The registry gets a fallback of konekt's own, so an undrawable
  component draws the same block an undecodable one does and reports through the same sink with the
  same `originalType`. The two failures are indistinguishable to a subscriber and should be
  indistinguishable to an operator; what differs is whose fault it is, and that is a question for the
  record rather than for the screen.
- The rejected alternative is a test that requires every dictionary type to have a renderer.
  `KonektRendererCoverageTest` is already that test and it PASSED: it holds the two lists apart and
  treats "not yet rendered" as a decision, which is exactly what it was. A list that records a gap is
  not a guard against the gap reaching a screen.
- Not covered: writing the six missing renderers. That is `B-45`, and this item is what makes their
  absence visible rather than silent while they are written.

- AC: a screen carrying a dictionary type with no renderer draws konekt's block, not the toolkit's red
  text, and the sink records it with that type — asserted against the stand, with the two failures
  distinguishable in the RECORD and not on the screen.
- AC: the guard fails when the fallback is removed, and `KonektRendererCoverageTest` still names which
  types are drawn, because the two lists are worth keeping for a different reason.
- Anchors: `client/src/commonMain/kotlin/io/konekt/client/render/KonektRenderers.kt`,
  `client/src/commonMain/kotlin/io/konekt/client/render/UnknownBlockRenderer.kt`,
  `client/src/jvmTest/kotlin/io/konekt/client/render/KonektRendererCoverageTest.kt`.

Background: [B-05](B-05-unknown-component-renderer.md) for the block this reuses,
[B-43](B-43-client-composition-root.md) for how it was found.
