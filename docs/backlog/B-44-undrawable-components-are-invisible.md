---
id: B-44
title: "A component that decodes and cannot be drawn is invisible from every guard"
status: done
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

## What landed

Every one of the nine dictionary types is registered now, and the six with no renderer of their own
draw the degradation block deliberately — `UndrawableComponentRenderer`, which reports the type and
then delegates the drawing to `UnknownBlockRenderer` rather than copying its Card and Line. Two
placeholders that drift apart is a screen that says one thing in one place and another in the next.

**The two failures are identical on screen and different in the record.** A subscriber meets the same
block and the same sentence, because "update to see it" is the only move either one leaves them.
`KonektDegradation.Cause` is what tells them apart for whoever reads the record: `UNDECODABLE` says
the client is behind the server, `UNDRAWABLE` says this build shipped a dictionary entry it never
wired up.

The cause needed a method of konekt's own. kompot's `onUnknown` is about the WIRE, which the toolkit
owns; "in konekt's dictionary with no renderer" is a fact about konekt's registry the toolkit has no
way to learn. A deployment binding some other sink still hears about the component and simply cannot
be told which of the two happened.

**The dev screen carries one of each now**, which is the only place they are ever drawn side by side —
`step_meter` beside two `esim_transfer_widget`. `ClientAgainstStandTest` asserts three records, two
causes and both types; mutation-proved by taking the registration back out, which fails two tests.

The guard that did not exist now does: `every dictionary type is registered, drawn properly or drawn
as a block`. The old `KonektRendererCoverageTest` passed throughout — it holds the two lists apart and
treats "not yet rendered" as a decision, which is exactly what it was until a served screen sent one.

## A defect this introduced, and the guard that caught it

Delegating to `UnknownBlockRenderer` meant TWO records for one component: one from this renderer with
the cause, one from the block with kompot's. Caught before it ran, and the block takes a `reports` flag
with exactly one caller passing `false`.

## And one this item inherited from B-35

`scripts/rolling-check.sh` extracts a copy of the repository at another commit into `.rolling/`, and
every source guard in this build walks the tree. Two `KonektClock.kt`, two `StatusPages.kt`, two of
everything — and the failure did not say so: `ClockUsageTest` reported *"KonektClock.kt is allowed to
read the system clock but does not exist"*, because `singleOrNull` over two matches is null.

`productionSources()` excludes `.rolling` now, and the assertion is split so "none" and "more than
one" say different things. The exclusion is in the walk rather than in the script's cleanup on
purpose: a guard that is correct only when a previous command tidied up is a guard with a precondition
nobody states.
