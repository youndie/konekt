---
id: B-52
title: "The balance block is four texts in the screen's own column, and the canvas draws a card"
status: done
priority: P1
size: M
stage: stage-m3-product
epic: feature-client-shell
---

# B-52 — There is no card, and the dictionary has nothing to make one with

Section 01 draws the balance as a filled card: `primary_container`, radius 36, padding 22, holding
the label, the amount at 36px, the number and both buttons. `HomeScreen.balanceBlock` returns four
components — `text`, `text`, `text`, `row` — as siblings in the screen's own column. So the
difference is not that the card is styled wrongly; there is no card, and nothing groups the four.

**The paragraph that was here was wrong, and the correction is worth more than the item.** It said
`KompotModifierNode` covers size and spacing and not background — read out of memory rather than out
of the artefact. The vocabulary in `kompot-core:0.33.1.91` is `Background(color)`, `Gradient(colors)`,
`Padding`, `Size` and `Weight`, and `KompotClientKt`'s modifier chain resolves a `Background` through
`KompotDesignSystem.resolveColor`. So the server could ALREADY say "this column stands on
primary_container", and a served brand kit could already repaint it.

**What is actually missing is one argument.** The chain calls
`Modifier.background(color, shape = null)`, so the fill is a rectangle, and there is no shape modifier
on the wire — deliberately: a radius is a client build constant (research §1.2, D2), which is what
lets brand B change `lg` 36→22 without a server release. The toolkit already knows what a card's
corner is here — `resolveSurface(KompotSurfaceRoles.Container).shape`, which every card renderer in
this client reads — and the modifier chain does not use it.

An ask filed from the original premise would have asked for the wrong thing, in the wrong size.

- **The decision this item exists to take.** Three candidates, and they are not equal:
  1. **A konekt component `balance_card`** — a tenth wire type, one renderer, and it says exactly one
     thing. Cheapest to build and the least general: the next grouped block needs an eleventh.
  2. **A `surface` container in the dictionary** — a component with children and a colour ROLE
     (not a colour), which the client resolves through the design system. General, and it is the
     first konekt component that is a container rather than a leaf.
  3. **Upstream**: a surface variant on kompot's `column`. The right home if the toolkit wants it —
     and the reason to ask rather than build is that every backend-driven product hits this on its
     first screen. Write it up in research-upstream-proposals and ask before filing.
- **Not covered:** the header and the avatar above the card — that is [B-55](B-55-home-header.md),
  and it needs data rather than a component.
- AC: the balance is one node in the tree, and its ground is a colour ROLE the served brand kit
  resolves — swapping to brand B repaints it without a client release.
- AC: whichever of the three is taken, `KonektRegistrationTest` and the dictionary lists move with
  it in the same change; `bottom_nav` is the precedent.
- Anchors: `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt`,
  `shared/components/src/commonMain/kotlin/io/konekt/components/`,
  `docs/design/design-app-canvas.md`.

Found by holding the served tree against the canvas markup rather than by looking at a screenshot —
[B-51](B-51-the-screens-against-the-canvas.md) has the method and the rest of what it found.

## What landed

**Option 2, with option 3 filed rather than deferred.** `konekt.surface` — a component with children,
a `tone` naming a colour ROLE, and a renderer whose whole job is `clip(shape).background(role)`. It is
the eleventh name in a dictionary whose other ten are product concepts, and the only container among
them; the file says outright that it exists for one missing argument and names the issue that would
delete it.

**[kompot#95](https://github.com/youndie/kompot/issues/95) (U14)** asks for that argument: paint a
`Background` with the container surface's shape, or give the modifier an optional role. The second is
offered as the one that cannot surprise an existing consumer — this is a toolkit with other users, and
changing what every existing `Background` looks like is a compatibility question rather than a fix.

**The claim it proves is the product's own.** Brand A draws the balance on a mint `primary_container`
with a 36-ish corner; brand B on the ink palette's with a tighter one — same tree, same markup, a
redeploy for the colour and a client constant for the shape. That was `B-22`'s claim demonstrated on a
pair of component cards; it is now demonstrated on a screen, which is where a hard-coded colour would
actually show.

**Ordering that is easy to get wrong:** `clip` before `background`. The other way round paints the
rectangle and then clips the layout, leaving square corners of ground behind rounded content — which
is precisely what the modifier chain does today and the reason this component exists.
