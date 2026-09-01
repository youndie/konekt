---
id: B-109
title: "Two kinds of card sat side by side with different insets"
status: done
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

## What it actually was

Neither suspect. The card is inset by 20 and the travel card immediately below it by 16, because
**each renderer spelled its own number**:

| Renderer | Inset |
|---|---|
| `SurfaceRenderer` — the allowance block, the balance block | `20.dp` |
| `UsageCounterCardRenderer` (not inline) — the travel package under it | `16.dp` |
| `PlanCardRenderer`, `BannerRenderer`, `UnknownBlockRenderer`, `ListRenderers` | `16.dp` |

Four pixels, and a step in a line that should be straight. Nothing was wrong with either number on its
own, which is why nothing caught it: **a repeated coordinate does not fail, it drifts**, and it only
becomes visible where two of the copies end up side by side. That happened when `B-105` grouped the
counters into a surface and left a roaming card underneath.

## How it was found

Not by reading modifiers — I read them first and concluded the alignment was fine, twice, off the
committed goldens. What settled it was fetching the tree the DEPLOYMENT was sending for the reporter's
own account, recording it as a fixture, and rendering it at the width of the window they were looking
at. The step is unmistakable there and arguable at a phone's width.

The server was innocent throughout: the card arrives as a `surface` with a head row and three
`usage_counter_card`s carrying `inline: true`, which is exactly what `B-105` specified.

## What was done

`CardGeometry` — one file, one inset, one fallback shape — and every card renderer now asks it.

**Sixteen and not twenty**, and that is the smaller change rather than the better number: five of the
six renderers already drew 16, so this brings the odd one into line and moves nothing else. Twenty-two
is what the canvas asks for, and it is paired there with a radius of 36 against the 20 this build
draws — moving one without the other would be worse than either. That pairing is
[B-112](B-112-the-cards-do-not-use-the-canvas-geometry.md).

## What the goldens say

Ten snapshots moved and all ten are home screens — the only place a `surface` and a card sit together.
Read rather than accepted: the balance block, the allowance block and the travel card now start on one
vertical, at both widths.

`App home wide` is kept as a permanent frame. This defect needed two different KINDS of card adjacent
with their left edges visible, and at a window's width the four pixels sit under a much longer line.

## Acceptance criteria

- AC: the answer comes from a **screenshot** compared against the canvas, not from reading the
  modifiers. Every defect this screen has produced so far was invisible in the component tree — the
  bar filling in the wrong direction, two buttons the same colour — and this is the same kind.
  **Held, and it was needed:** reading the modifiers produced the wrong answer twice.
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
