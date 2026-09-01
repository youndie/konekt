---
id: B-110
title: "The four tabs are words with no icons, because kompot has no icon vocabulary"
status: open
priority: P2
size: M
stage: stage-m7-completeness
---

# B-110 — Home, Plans, Orders, Profile — and nothing above them

The canvas draws an icon over each tab. This build draws four labels, and `BottomNavRenderer` says
why in as many words:

> LABELS AND NO ICONS, because kompot has no icon vocabulary — no wire type, no token — and an icon
> name here would be a string this client maps to a drawable it compiled in, which is a second
> dictionary kept in step by hand.

That reasoning is still right, and it is the reason this is an **M** rather than an **S**: the cheap
version is exactly the thing the comment refuses.

## Why the obvious fix is the wrong one

Adding `icon: String` to `BottomNavItem` and a `when (icon)` in the renderer gives the server a
vocabulary the client must already know. It is the same shape as `konektActionWireNames` and the
component dictionary — two lists that must agree, with nothing holding them together — except worse,
because the failure is silent: an unknown name draws nothing, and a tab with no icon looks like a tab
whose icon has not loaded.

Whatever is built here needs the property the rest of the wire has: **a name the client does not know
must fail loudly, in a test, before a deployment draws it.**

## The three shapes, and none is free

| | What it costs | What it buys |
|---|---|---|
| A closed enum on the wire, generated into the client like the components are | a client release per icon | the compiler holds both sides together |
| A name plus a client-side table, guarded by a test that the table covers the enum | a client release per icon, plus a guard | the same, with more moving parts |
| The icon as **data** — an SVG or a vector path on the wire | no client release at all | a renderer that draws arbitrary vectors, and a server that owns the drawing |

The third is the only one that makes an icon a server decision, and it is also the only one that
changes what kompot is for. It deserves the upstream conversation the comment already points at
rather than a decision taken here on a Tuesday.

## Acceptance criteria

- AC: the choice among the three is written down with its reason before anything is built, and
  `operator-boundaries.md` gains the row for whichever it is — an icon change costing a client
  release is exactly the kind of price that document exists to state.
- AC: a name the client cannot draw fails a test rather than drawing nothing.
- AC: whatever lands is exercised by a screenshot, not only by a tree assertion. A bar with the right
  icon names and no icons on screen would pass every assertion this build currently makes.
- AC: if the answer is an upstream ask against kompot, the issue is filed there and this item cites
  it. `youndie/*` may be filed directly.

## Anchors

| What | Where |
|---|---|
| The bar | `shared/components/src/commonMain/kotlin/io/konekt/components/BottomNavComponent.kt` |
| The renderer, and the reasoning as it stands | `client/src/commonMain/kotlin/io/konekt/client/render/BottomNavRenderer.kt` |
| Who decides the tabs | `server/src/main/kotlin/io/konekt/screens/Shell.kt` |
| What a wire change costs | [operator-boundaries](../services/operator-boundaries.md) |
