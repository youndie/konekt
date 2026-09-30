---
id: B-116
title: "The purchase confirmation is a screen of its own, not a sheet over the plan page"
status: done
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

## Done — 2026-09-30

**The server asks per answer, not per address.** The first attempt read this item as "a route with
`presentation = sheet`" (kompot `B-69`, `ScreenRoute.presentation`) and stopped before writing code,
because konekt reaches the confirmation without the graph at all: `Buy` is `buy_plan`, the runner
posts it and builds the order's address itself (`BuyPlan`), and the one deeplink for an order,
`app://order/<id>`, is parameterised and deliberately not in the graph (`U15`). And the address is not
the subject anyway — `GET /api/v1/screens/orders/{orderId}` answers five states, and only one of them
is the canvas's sheet. kompot answered with `X-Kompot-Presentation` (its `B-70`, `0.39.0.193`): the
response says how to show THIS answer. `PurchaseResultScreen.presentation` answers `sheet` for
`awaiting_confirmation` and nothing for the other four, exhaustively, and the route sets it before
the body. The tree did not change.

**The client reads it where the headers are.** `KonektScreenSource.fetch` resolves the header through
`PresentationHeader.presentedAs` against what this build draws (`KonektSheetHost.DRAWS` — screen and
sheet) and carries the answer on `Screen.Tree.presentation`. `KonektApp` now reads the answer to a
press BEFORE moving the stack: a `sheet` goes into kompot's layer through the chain
(`withOverlays`, as a `present`) and the plan page stays mounted underneath; anything else moves the
stack as before, reusing the fetched answer so nothing is asked for twice.

**The sheet is konekt's own host, not Material's.** `KonektSheetHost` reads
`KompotOverlays.presented` (public since kompot `B-66`) and draws the canvas's frame 07: the page's
ink at 42% as the scrim, the brand's large radius on the top corners, a 44 × 5 handle in the kit's
outline colour, a 20-point inset. It closes through `dismiss()` only — a tap on the scrim or a drag
down past 120 points (or a flick) — and a short drag springs back.

**What closes it, and why one of them had to be written.** `Not now` is a `navigate`, and kompot's
chain closes the layer before the move (its `B-67`): the subscriber lands home with no sheet. `Pay` is
not: it is `confirm_purchase`, answered by the runner with an address, which never passes through the
chain as a `navigate` — so `B-67` does not reach it, and the holder closes the layer itself whenever
an answer moves the stack. `ConfirmationSheetTest` covers both, and the mutation that removes the
holder's close fails `pay leaves no sheet behind`.

**Degradation.** A client that predates the header never reads it and shows the confirmation as the
screen it always was — the `App confirm` golden still photographs that. A client that cannot draw a
sheet (`presentations = setOf(screen)`), or meets a word it does not know, does the same, covered by
`a client without a sheet shows the confirmation as a screen` and by the source test.

**Left out, on purpose.**

- The confirmation reached any other way is still a screen: a history row (`app://order/<id>`) and the
  custom package's submit are `navigate`s, and a `navigate` moves the stack before anything is
  fetched. Reading the answer first there too would change every navigation in the product for two
  entry points the canvas does not draw as a sheet.
- `Not now` inside the sheet still goes home, as it did on the screen. Making it return to the plan
  page needs `close`, which a konekt client released before this one answers with nothing — a dead
  link on exactly the client the header degrades for. The scrim and the drag are the way back to the
  plan page.
- No system back gesture closes the sheet: nothing in this client handles one yet, on any screen.
- No animation: the sheet appears and disappears. The canvas is a still frame.
- `confirm` (a question over a screen) is not drawn: konekt sends none.

## Anchors

| What | Where |
|---|---|
| The content, and the one state that asks to be a sheet | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt` — `awaitingConfirmation`, `presentation` |
| The header on the route | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseRouting.kt` |
| Reading it | `client/src/commonMain/kotlin/io/konekt/client/app/KonektScreenSource.kt` — `fetch` |
| The sheet | `client/src/commonMain/kotlin/io/konekt/client/app/KonektSheetHost.kt` |
| The tests | `client/src/jvmTest/kotlin/io/konekt/client/app/ConfirmationSheetTest.kt`, `e2e/src/test/kotlin/io/konekt/e2e/PurchaseScenarioTest.kt`, `client/src/jvmTest/kotlin/io/konekt/client/stand/ClientAgainstStandTest.kt` |
| The shell | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt`, `client/src/commonMain/kotlin/io/konekt/client/app/KonektShell.kt` |
| The canvas frame | `docs/design/audit-2026-09-02/design/07.png` |
| The parent | [B-114](B-114-the-client-does-not-look-like-the-canvas.md) |

## Where it was verified — 2026-09-30

- **Linux box:** `./gradlew build` green — `viddikVerify compared 77 screenshot case(s)`, the server
  suite and the conformance gate included. Then `make stand-up && make e2e` green: 34 `:e2e` cases and
  17 `:client:standTest` cases, among them the header check in `PurchaseScenarioTest` (the deployed
  route answers `X-Kompot-Presentation: sheet` while the order waits and no header once it is paid)
  and `with money on the line the confirmation is a sheet over the plan page and paying leaves none`
  in `ClientAgainstStandTest`. The stand was taken down after.
- **Mac:** `ConfirmationSheetTest` 8/8, `PurchaseResultScreenTest` 18/18, the iOS simulator suites
  (`:client` 5, `:shared:components` 7) and the simulator link, `make check`, `viddikVerify` 77 cases.
- **Mutations, each caught:** no close on a move (`pay leaves no sheet behind`), the source ignoring
  the header (five cases), a dead scrim, a sheet any drag closes, a holder that draws every answer as
  a screen (four cases), every order state asking for a sheet (`only the confirmation asks to be a
  sheet`).
- **The new frame:** `App confirm sheet`, light and dark — the plan page for `tr-10gb-30d` under the
  confirmation for the same plan, both captured from one stand; the plan page recording came back
  byte-for-byte equal to the committed one. `viddikRecord` wrote exactly those two goldens and kept the
  other 75. Against canvas frame 07: the dimmed page, the 36-point top corners, the handle, the table,
  the chosen source and `Pay $12` match; the canvas's second source (a card) and its terms checkbox
  are not drawn — the content is `B-114`'s and did not change here, and there is one source (`B-40`)
  — and the frame has no back chevron because the fixture's plan page is the root of its stack.
