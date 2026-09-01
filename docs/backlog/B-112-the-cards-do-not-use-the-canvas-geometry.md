---
id: B-112
title: "Every card in the build is a radius and an inset the canvas does not pair"
status: done
priority: P3
size: M
stage: stage-m7-completeness
---

# B-112 — The build agrees with itself and not with the canvas

`B-109` made every card in this client ask one place for its inset. That settled the disagreement
**between our own cards**. It did not touch the disagreement with the design.

The canvas pairs a corner radius with an inset, consistently:

| Radius | Inset | Times it appears |
|---|---|---|
| 36 | 22 | 10 |
| 22 | 18 | 10 |
| 20 | 14 | 7 |
| 20 | 11 | 9 |

This build draws **radius 20 with inset 16**, which is not a pair the canvas uses anywhere. Read out
of `docs/design/konekt-esim-app.dc.html`.

## Why it was not done with B-109

Because moving one of the two numbers is worse than moving neither. A 36-point radius with a 16-point
inset is not "closer to the design"; it is a third geometry that nothing asked for. The pair has to
move together, and moving it changes **every card on every screen** — which means re-reading every
golden against the canvas rather than accepting a diff of thirty images.

That is a design pass with a person looking at it, not a constant swap.

## What the canvas actually says, once it was counted

The item was filed off a frequency count of `border-radius:R;padding:P`, which was enough to see a
disagreement and not enough to fix one. Reading what sits INSIDE each pair turns it into a scale:

| Pair | What it holds |
|---|---|
| 36 / 22 | Balance, Smart 20, Data remaining, Data plan — a screen's **headline blocks** |
| 22 / 18 | Turkey, Georgia, Serbia, an order row — the **items in a list** |
| 22 / 16 | Reversed, Installing on this phone? — **notices** |
| 20 / 14 | eSIM ready to install, Card ···4417 — **compact rows** |

The bigger the block, the rounder and the roomier. This build drew one pair for all six of its card
renderers, which is what makes a screen read as flat.

**And the 22s are not brand B's showcase**, which was the first thing worth ruling out: brand B's
section starts at offset 111830 and every 22/18 sits between 31263 and 87033.

## The split, and why the pair still moves together

**The radius is the brand's; the inset is ours.** That is not a compromise — it is the split this
build already documents. A radius travels with a brand and costs a client release
([operator-boundaries](../services/operator-boundaries.md)); an inset is layout. What must move
together is the **tier**: a headline block takes the large radius AND the large inset, and
`CardGeometry.Tier` is that.

The radii come from the canvas's own **token block** — `sm 12/8 | md 20/12 | lg 36/22`, brand A then
brand B — which is exactly `KonektShapeScale`. Several brand-A mockups draw a list card at 22, brand
B's large; the declared token is taken over the drawing, because a token block is what a design SAYS
and a mockup is a picture of one screen.

Getting there needed one new seam: `LocalKonektShapeScale`. The design system answers a single
container role, so every card resolved to `md` and no tier could be expressed through it — and
kompot's role vocabulary has `button(name)` and `checkboxInput(name)` but no `container(name)`.
Providing the scale beside the design system is konekt's own and needs no upstream change.

**The renderers stopped asking the container role for a card's shape.** The first attempt kept
`surface.shape ?: CardGeometry…` and moved nothing at all: the role always answers, so the fallback
never ran. A tier that only applies when the design system declines is not a tier.

## The thing this closed that nobody was looking for

`KonektShapeScale` carried a property called `largeIsDrawn`, and an `InertRadiusIsDeclaredTest` that
held brand A's `lg` to being **drawn by nothing**: `largeShape` was read by `buttonShape` alone and
only when pills were off, so brand A stated a 36 that no golden could see. It was measured — setting
it to 8 moved nothing — and the test said what to do if it ever changed:

> if that is now true, `B-28`'s second acceptance criterion has become satisfiable and should be
> reinstated rather than this line relaxed

It is now true, and the reinstatement is the honest one rather than the rejected shortcut of inventing
a surface to make a golden bite: the canvas pairs headline blocks with `lg` all along.

**Measured.** Changing brand A's `lg` from 36 to 8 and diffing two recorded sets:

```
16 goldens move — every home frame, both Brand_A frames, the five counters, the recorded home screen
0 of brand B's
```

which is `B-28`'s second AC satisfied for the first time. `EveryStepOfTheScaleIsDrawnTest` replaces
the old one with the mirror statement: **no step of a brand's scale is inert.**

## What the goldens say

Twenty-eight moved and all were read. The home screen now has three headline cards at 36 with a
notice at 20 under them, and the plans screen a list of items at 20 — the hierarchy the canvas draws
and this build did not have. Nothing looked worse; the one thing worth saying out loud is that brand
A's cards are noticeably rounder than before, which is the design's own number and not a taste
applied here.

## Acceptance criteria

- AC: the pair moves together, and `CardGeometry` stays the one place either number lives. **Held
  with one amendment stated above**: the radius half lives in the brand's scale, because it is the
  brand's to own. `CardGeometry` is the one place the PAIRING lives.
- AC: which pair is chosen is decided per card SIZE. **Three tiers**, and the mapping is in the
  table above.
- AC: every golden that moves is looked at, and the ones that look worse are said so out loud.
  **Twenty-eight, read.**
- AC: `design-app-canvas.md` records the decision. **Done**, with the token-over-mockup rule; and
  `design-brand-kit.md` and `B-28` are corrected where they recorded the inertia as permanent.

## Anchors

| What | Where |
|---|---|
| The one place | `client/src/commonMain/kotlin/io/konekt/client/render/CardGeometry.kt` |
| The canvas | `docs/design/konekt-esim-app.dc.html`, `docs/design/design-app-canvas.md` |
| What settled the internal disagreement | [B-109](B-109-the-allowance-card-pads-twice.md) |
