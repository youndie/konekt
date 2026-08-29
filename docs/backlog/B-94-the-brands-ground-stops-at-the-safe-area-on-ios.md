---
id: B-94
title: "On iOS the brand's ground stops at the safe area, so the status bar and the home indicator are black whatever the served palette is"
status: done
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

## What was done

One line in `scripts/ios-home-app.sh`:

```xml
<key>UILaunchScreen</key><dict/>
```

**The cause was not the safe area at all.** A bundle with no launch screen tells iOS it was built for
a legacy display, so the system runs it **letterboxed** — a compatibility canvas smaller than the
screen, with black bands the system draws *outside* the app's window. The brand's ground was reaching
the edges of that canvas correctly the whole time.

The same declaration went into `scripts/ios-crash-app.sh`, so the two hand-written bundles do not
differ in a way somebody has to rediscover.

## How it was found, and what the wrong turn was

Three measurements, and the second one produced a wrong conclusion that the third corrected:

1. **The strips survived turning the frame's inset padding on and off.** So they were not the padding.
2. **Painting the `UIWindow` magenta did not tint them.** So they were not the window either — which
   left "the compose view paints them", and that was the wrong inference. They were drawn by the
   system, outside the app entirely.
3. **`ComposeUIViewControllerConfiguration` has no safe-area knob** — `opaque`,
   `enforceStrictPlistSanityCheck`, `delegate` and a gesture setting, read out of the klib's metadata.
   That is what made "compose is padding its content" untenable and sent the search back to the
   bundle.

### And it invalidated an earlier "correction"

Verifying `B-88` on the simulator appeared to show the frame's `windowInsetsPadding(safeDrawing)`
applying the inset twice on iOS — the login title 55 device pixels lower with it than without. That
measurement was real and the conclusion drawn from it was wrong: **the safe area of a letterboxed
canvas is not the safe area of the screen.** With the launch screen declared, iOS needs the padding
exactly as Android does — the title went straight back under the status bar without it.

So the parameter added for that is gone and `KonektApp` carries the single rule again, with the story
in the comment. *A measurement is only as true as the thing it was taken on* — and the thing it was
taken on was a defect.

## Verified

On the simulator, in four builds: the served kit's surface reaches all four edges of the screen, and
the login title is clear of the status bar. `./gradlew check` green, `ktlintCheck` green.

## What is deliberately not in scope

**The status bar's own content.** With the app now owning that strip, nothing draws a clock or the
indicators in it — and `simctl status_bar override` does not make them appear, so the bar is hidden
rather than mis-coloured. Two plist keys were tried, changed nothing observable, and were removed
again. That is [B-95](B-95-the-ios-bundle-draws-no-status-bar.md), with the leading hypothesis written
as a hypothesis.

## Anchors

| What | Where |
|---|---|
| The declaration | `scripts/ios-home-app.sh`, `scripts/ios-crash-app.sh` |
| The single inset rule, restored | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt` |
| The host it applies to | `client/src/iosMain/kotlin/io/konekt/client/ios/HomeEntryPoint.kt` |
