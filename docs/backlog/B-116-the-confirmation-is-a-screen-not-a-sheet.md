---
id: B-116
title: "The purchase confirmation is a screen of its own, not a sheet over the plan page"
status: open
priority: P3
size: M
stage: stage-m7-completeness
---

# B-116 — The confirmation as a sheet

The canvas presents the purchase confirmation as a **bottom sheet over the plan page**, with a drag
handle and the plan's hero still visible behind it. This build presents the same content as a screen
of its own: the plan page navigates to it, and the way back is the shell's chevron.

Everything on the sheet is already drawn — [B-114](B-114-the-client-does-not-look-like-the-canvas.md)
block 5 made the content the canvas's (a title, the plan and the price as a table, `Pay from` over
the one source drawn as the chosen option, `Pay $X`, the hold sentence under it, `Not now` as a
link). What is left is the presentation, and it was left on purpose: a sheet changes how the client
navigates — a modal layer over a screen that stays mounted, with its own back gesture — and nothing
about what the server sends. It is the last item of B-114's order and the one with the least on the
wire, so it is its own item rather than a half-done tail of that one.

## What it takes

- The client keeps the plan page mounted under the confirmation and draws the confirmation in a
  sheet with a handle; `Not now` and the drag both dismiss it, and dismissing is the `Not now` path
  (the order keeps its deadline and rolls itself back — the compensated branch this product exists
  to demonstrate).
- A way for the server to say "this screen is a sheet" without naming a presentation: the smallest
  candidate is a hint on the `screen_header` (`B-115`) or on the route, and it has to degrade to the
  screen this build draws today on a client that predates it.
- A golden of the sheet over the plan page, in both themes, and the sheet's dismissal covered the way
  `BackControlTest` covers the chevron.

## Acceptance criteria

- AC: the confirmation opens over the plan page as a sheet on every platform the client runs on, and
  dismissing it leaves the plan page where it was.
- AC: whatever goes on the wire is priced in [operator-boundaries](../services/operator-boundaries.md).

## Anchors

| What | Where |
|---|---|
| The content | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt` — `awaitingConfirmation` |
| The shell | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt`, `client/src/commonMain/kotlin/io/konekt/client/app/KonektShell.kt` |
| The canvas frame | `docs/design/audit-2026-09-02/design/07.png` |
| The parent | [B-114](B-114-the-client-does-not-look-like-the-canvas.md) |
