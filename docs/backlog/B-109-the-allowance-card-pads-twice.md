---
id: B-109
title: "The allowance card has an extra inset around the three counters"
status: open
priority: P3
size: S
stage: stage-m7-completeness
---

# B-109 — Data, Minutes and Messages sit further in than they should

Reported from the running desktop client against the stage deployment: inside the **Your allowance**
card, the three counter rows carry an extra inset — they do not line up with the card's own head.

## What is established

The double-ground problem `B-105` was written for is **already solved**: `UsageCounterCards.of(…,
inline = true)` makes each counter a row rather than a card, and `UsageCounterCardRenderer` skips the
`clip` / `background` / `padding(16.dp)` in that branch. So this is not the inline flag failing.

What remains, and is only a candidate until measured:

| Suspect | Where |
|---|---|
| `SurfaceRenderer` pads every surface by a flat `20.dp` | `client/…/render/SurfaceRenderer.kt` |
| the frame pads the content box by `20.dp` horizontally as well | `client/…/app/KonektApp.kt` |
| `spacing = 16` between the head and the rows | `server/…/screens/HomeScreen.kt` — `allowanceCard` |

Three insets in a row is enough to look wrong without any of them being wrong on its own, and which
one is the extra is a question for the canvas rather than for arithmetic.

## Acceptance criteria

- AC: the answer comes from a **screenshot** compared against the canvas, not from reading the
  modifiers. Every defect this screen has produced so far was invisible in the component tree — the
  bar filling in the wrong direction, two buttons the same colour — and this is the same kind.
- AC: whatever moves, it moves in ONE place. A card that lines up because two paddings were nudged to
  cancel is a card that stops lining up the next time either is touched.
- AC: if the flat `20.dp` in `SurfaceRenderer` is what is wrong, the fix is not a special case for
  this card: every surface in the build is drawn by that line.
- AC: `viddik` goldens are regenerated and read, not just accepted.

## Anchors

| What | Where |
|---|---|
| The card | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` — `allowanceCard` |
| The inline row | `client/src/commonMain/kotlin/io/konekt/client/render/UsageCounterCardRenderer.kt` |
| The surface's padding | `client/src/commonMain/kotlin/io/konekt/client/render/SurfaceRenderer.kt` |
| The frame's padding | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt` |
| The card this came from | [B-105](B-105-the-home-screen-diverges-from-the-canvas.md) |
