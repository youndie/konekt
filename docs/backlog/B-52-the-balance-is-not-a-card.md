---
id: B-52
title: "The balance block is four texts in the screen's own column, and the canvas draws a card"
status: open
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

**Why it cannot be done today.** kompot's standard vocabulary has `column` and `row` and no
container that carries a surface: `KompotModifierNode` covers size and spacing, not background or
corner radius, and the wire has no vocabulary for shape at all (research-architecture §1.2 — radii
are a client build constant, deliberately). konekt's own nine components are all leaves. So a filled,
rounded group is not something the server can currently say.

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
