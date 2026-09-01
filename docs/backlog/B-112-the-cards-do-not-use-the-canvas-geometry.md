---
id: B-112
title: "Every card in the build is a radius and an inset the canvas does not pair"
status: open
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

## Acceptance criteria

- AC: the pair moves together, and `CardGeometry` stays the one place either number lives.
- AC: which pair is chosen is decided per card SIZE if the canvas distinguishes them — it uses 36/22
  for the large blocks and 22/18 for the smaller ones, and flattening both into one value here would
  be inventing a design the canvas does not have.
- AC: every golden that moves is looked at against the canvas, and the ones that look worse are said
  so out loud rather than accepted because the number is now "right".
- AC: `design-app-canvas.md` records the decision, including any place this build deliberately keeps
  its own geometry.

## Anchors

| What | Where |
|---|---|
| The one place | `client/src/commonMain/kotlin/io/konekt/client/render/CardGeometry.kt` |
| The canvas | `docs/design/konekt-esim-app.dc.html`, `docs/design/design-app-canvas.md` |
| What settled the internal disagreement | [B-109](B-109-the-allowance-card-pads-twice.md) |
