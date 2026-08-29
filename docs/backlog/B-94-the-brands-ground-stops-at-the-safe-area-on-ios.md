---
id: B-94
title: "On iOS the brand's ground stops at the safe area, so the status bar and the home indicator are black whatever the served palette is"
status: open
priority: P2
size: S
stage: stage-m7-completeness
---

# B-94 — Two strips of the screen a served brand cannot repaint

`ComposeUIViewController` lays its content inside the safe area. `KonektApp` paints
`M3Colors.Surface` over `fillMaxSize()`, and `fillMaxSize()` fills the area it is GIVEN — so the
brand's ground reaches the edges of the compose content and not the edges of the screen. What is
left is the `UIWindow`, which `KonektAppDelegate` creates and never gives a background, and an
unbacked window is black.

Measured on the simulator during `B-88`'s verification, in three builds taken minutes apart — with
the frame's inset padding on, off, and made a parameter. **The strips are identical in all three**,
so they are not the padding: they are what the compose view does not cover.

| | |
|---|---|
| Where | the status-bar strip and the home-indicator strip, permanently, on every screen |
| What is drawn there | black — `UIWindow` with no `backgroundColor` |
| What should be | the served kit's surface, the same colour the rest of the screen is |

It is small on screen and it is not small as a claim. `operator-boundaries.md`'s first row says
colours ship from the server and the client applies them without a rebuild; these two strips are the
one part of an iOS screen that a brand kit cannot reach. Android has the same shape of problem and
`B-85` closed it — the ground is painted under the insets and the bars are transparent, so a dark
brand would darken them.

- **The decision is to make the compose content span the whole window, not to paint the window.**
  Setting `UIWindow.backgroundColor` needs a colour, and the only side that knows the brand's surface
  is inside the composition — so a colour chosen in `KonektAppDelegate` would be exactly the "one
  surface a brand cannot repaint" this item is about, moved one layer down.
- **What that probably takes**, and it is a research question rather than a known line:
  `ComposeUIViewController` takes a `configure` lambda, and whether CMP 1.11 exposes a way to stop it
  consuming the safe area — or whether the answer is a host view controller that ignores it — was not
  established. The klib's metadata was read far enough to know the knob is not in `ui-uikit`.
- **If it turns out CMP cannot be told**, the honest outcome is a row in `operator-boundaries.md`:
  *the system-bar strips on iOS — **not available**, the fifth cost*. That is a worse product and a
  true document, and this repository prefers the second to a sentence that is wrong.
- This item does **not** touch the frame's inset parameter, which is settled: iOS passes
  `appliesWindowInsets = false` because the host already insets, and that was measured the same day.

- AC: on the simulator, the status-bar and home-indicator strips are the served kit's surface colour,
  and switching to brand B changes them with everything else.
- AC: no colour for them is named outside the composition — a literal in the delegate or the plist
  would be the defect this item is about.
- AC: if the above cannot be done against CMP 1.11, `operator-boundaries.md` gains the row and
  `reference-scope.md` the reason, and this item closes as a boundary rather than as a fix.
- Anchors: `client/src/iosMain/kotlin/io/konekt/client/ios/HomeEntryPoint.kt`,
  `client/src/iosMain/kotlin/io/konekt/client/ios/HomeApp.kt`,
  `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt`,
  `docs/services/operator-boundaries.md`.
