---
id: B-51
title: "Every screen photographed and held against the canvas, and what the two disagree about"
status: done
priority: P1
size: L
stage: stage-m3-product
epic: feature-client-shell
---

# B-51 — What the screens and the canvas disagree about, screen by screen

Every screen this build serves is now a golden, recorded off a running deployment and rendered
through the application's own composition root — `docs` has the list in
[design-app-canvas](../design/design-app-canvas.md), `client/src/jvmTest/snapshots/Gallery_*.png`
has the photographs. This item is what looking at them found.

## Three things are wrong on every screen at once

- **No margin.** Every screen starts at x=0 and runs to the edge. The canvas gives all nine frames a
  side margin, and the difference is visible on all six photographs before any content is compared.
  It belongs to the client's frame rather than to a tree — a `padding` modifier on every root would
  be the server saying the same thing seven times.
- **The bottom bar is not pinned.** It arrives as the last child of the column, so it sits under the
  content instead of at the bottom of the screen — on the home screen it lands a third of the way
  down. `BottomNavComponent` says the shell HOISTS it; nothing hoists it yet. `B-49` shipped the bar,
  and this is the half that was left.
- **Buttons hug their text.** "Send me a code" and "Sign out" are as wide as their label; the canvas
  draws them full-bleed. kompot's modifier vocabulary has `Size` and `Weight`, so the server can say
  this — which is the answer that keeps the decision on the side that owns the design.

## Per screen

| Screen | Against the canvas |
|---|---|
| Login | The tree matches section 09 exactly. Missing the example number until `v0.1.4` ships the placeholder, and the button width above. |
| Login code | Matches, including the bound `read_only_field` drawn with no fill and no border. |
| Home | The three cross-cutting items, plus everything section 01 draws and this build does not serve: the header, the number, `Top up` / `History`, the plan name with its renewal date, `Buy a package`, and two of the three counters — only `DATA` is ever granted, so *Running low* and *Used up* cannot appear beside a normal counter on any real account. |
| Plans | The closest of the six. Sold-out is marked rather than hidden, prices are right-aligned, badges draw. The canvas's **alternating tonal card** is not implemented — all four cards carry one tone. |
| Orders | Four lines of text and nothing else — no card, no surface, no reference line, and no bar because the screen is built in the purchase feature and nothing hands it one. Section 05 draws every order as a card with its reference under the title. It is also the one screen with NO committed golden: at 4% drawn, `GoldenContentTest` cannot tell it from a capture that failed, and it is right not to. |
| Profile | Matches what it claims. The canvas's other four rows name features this product does not have — see `B-50`. |
| Purchase result | Not compared yet against section 03, which draws four states; the recording is of the completed one. |

## One thing that was a defect rather than a difference

**The orders screen crashed the application.** `PaginatedListRenderer` reads a `KompotPageLoader` out
of a composition local and throws when there is none, whether or not the list has a next page —
`KonektApp` provided none, so opening Orders answered `IllegalStateException: LocalKompotPageLoader
not provided`. Nobody had found it because the screen was unreachable until `B-49` gave it a tab; the
screenshot harness found it in the same hour. Fixed by putting `pages()` on `ScreenSource`.

- AC: a screen's frame has the canvas's margin, and the bar is at the bottom of it.
- AC: an order is a card carrying its reference, and the orders screen carries the bar.
- AC: the home screen serves what section 01 draws, or the canvas records what this build will not
  serve — a difference is only a defect once somebody has decided which of the two moves.
- Anchors: `client/src/jvmTest/kotlin/io/konekt/screenshots/ScreenGalleryScreenshots.kt`,
  `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt`, `docs/design/design-app-canvas.md`.

Background: [B-49](B-49-the-app-has-no-shell.md) put the bar on the wire; [B-50](B-50-login-frame-six.md)
prices the login additions; [B-28](B-28-screenshot-tests.md) is the harness these frames are recorded by.

## What landed

**The three cross-cutting ones are fixed.** The frame is the client's — a margin around the content
and the bar lifted out of the root's own children and drawn at the bottom of the window. Full width
is said by the SERVER, through `Size(width = Fill)`, a modifier the vocabulary already had: the
renderer was the other candidate and is worse, because a client that made every button fill its row
would be a client with an opinion about layout.

**The order row has a card and its reference**, and the orders screen carries the bar — through a
port rather than a dependency, so the purchase feature asks for chrome by its own deeplink and never
learns what a bar is. Its golden is back: it was missing for one release because four lines of text
on nothing is 4% drawn, and the guard that refused it needed no changing.

**The home screen gained the number and a way to the catalogue.** `Top up` is deliberately absent:
topping up is a POST with an amount and this build serves no screen to choose one on, so the button
would navigate nowhere. That is a screen to build, not a layout to fix.

**And two defects fell out of looking.** A history row drew the plan's ID where its name belongs —
"eu-5gb-14d" — because an entitlement stores an id and nothing looked the name up. And a reference
with no date drew a dangling separator on the purchase-result screen, which the goldens caught the
same hour by refusing a build.

## What is left, and why each one is not a layout fix

- **The plans catalogue.** Reading section 02 properly rather than its one-line summary in
  `design-app-canvas.md`: it draws a search field, four filter chips, per-GB pricing, restock dates,
  a `Choose` button per card, and a whole plan-detail screen with a spec table. The summary called it
  "four list states in one frame", which is how an alternating card tone got attempted and reverted.
  **The summary is the thing that was wrong**, and a paraphrase that quietly replaces its source is
  worth more attention than the feature it mis-described.
- **Section 01's plan block** — "Smart 20 · renews 12 Sep" — needs a subscription with a renewal
  date. This build grants a counter and keeps no such entity.
- **Three counters.** `Plan` carries `dataMb` and nothing else, so MINUTES and MESSAGES are never
  granted and the canvas's *Running low* and *Used up* cannot appear beside a normal counter on any
  real account. It is the demonstration those two states exist for.
- **The header and avatar.** The brand name is not in the domain and neither is a subscriber's name.
  Drawing initials would mean inventing a field.
- **The margin and the pinned bar are not photographed.** The gallery renders a screen TREE; the
  frame belongs to `KonektApp`, which the goldens do not go through. Verified by running the desktop
  client instead — which is a real gap in the harness rather than in the frame.

## What closed it

The third criterion — *the home screen serves what section 01 draws, or the canvas records what this
build will not serve* — and it took both halves.

**Served:** the header (the brand kit gained a `displayName`), the balance as a card on a ground the
brand paints, `Top up` beside `History`, all three counters with the states that were unreachable, and
**the install door**. That last one is what this item was really missing: the flow could be reached
from the purchase result and, once a history row carried an action, from there — both places somebody
has to think to go. It is a banner on the screen a subscriber opens, drawn on the COUNT of profiles
held rather than on there being a roaming package, because what makes an eSIM installable is holding
none and a home bundle needs one exactly as much as a trip does. Tying it to roaming would have been
the canvas's example mistaken for the rule.

**Recorded rather than served:** the avatar, the subscription heading, the `Roaming` button and the
roaming row, each with the reason and the item it belongs to, in
[design-app-canvas](../design/design-app-canvas.md). A difference nobody has decided about is a
difference that stays forever.

## The finding this item is actually about

Every frame-level defect in this product was found by a person looking at the running application
while the suite was green — the missing margin, the bar a third of the way down, the unpainted ground,
the back control on a tab. The goldens photographed the screen TREE and never the application, so none
of them could be in a frame. `AppFrameScreenshots` drives the same recordings through the real
composition root at the canvas's own frame height, and the class of defect stopped being invisible.

It is also the item that produced the method: **hold the served tree against the canvas markup, not
the screenshot against the drawing.** In a backend-driven product a mockup is an assertion about a
tree, and the raster only shows whether the renderer honoured it. Every subsequent finding — the
balance that was four siblings rather than a card, the card that already had the fields nobody sent,
the history rows carrying no action — came out of reading the two structures side by side, and none of
them is visible in a picture.
