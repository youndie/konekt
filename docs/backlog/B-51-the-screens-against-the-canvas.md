---
id: B-51
title: "Every screen photographed and held against the canvas, and what the two disagree about"
status: open
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
