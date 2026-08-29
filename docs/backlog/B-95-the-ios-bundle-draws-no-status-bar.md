---
id: B-95
title: "The iOS bundle draws no status bar at all — no clock, no indicators — and the cause is not established"
status: open
priority: P3
size: S
stage: stage-m7-completeness
---

# B-95 — A strip of the screen the system stopped drawing

`B-94` gave the hand-assembled iOS bundle a `UILaunchScreen`, which stopped iOS running it
letterboxed and let the brand's ground reach the edges of the screen. With the app now owning that
area, **nothing draws a status bar in it**: no clock, no signal, no battery.

## What was measured

| Attempt | Result |
|---|---|
| `UIStatusBarStyleDarkContent` with `UIViewControllerBasedStatusBarAppearance` false | no change |
| `xcrun simctl status_bar konekt-iphone override --time 9:41 --batteryLevel 100` | no change |

The second is the one that settles the question: an override that does not appear means the bar is
**hidden**, not drawn in a colour that matches the ground. So the two plist keys were removed again —
two keys that change nothing observable are worse than none.

It is a regression only in the narrow sense. Before `B-94` the clock WAS visible, on the black
letterbox band that the system drew *outside* the app; the app never drew it either. What changed is
that the app now owns the whole screen, so the absence is now the app's.

## The leading hypothesis, untested

`KonektAppDelegate` creates its own `UIWindow` and calls `makeKeyAndVisible` from
`application(_:didFinishLaunchingWithOptions:)`. There is no `UIApplicationSceneManifest` in the
bundle, so there is no `UISceneDelegate` and the window may never be attached to a window scene — and
the status bar belongs to the scene. An Xcode-generated app has one; a hand-written bundle has
whatever it declares.

That is a hypothesis and it is written as one. It was not tested, because the alternative to writing
it down was another round of guessing at plist keys, and this repository has a rule about that.

- **The decision: establish the cause before changing anything.** Either the window needs a scene, or
  `ComposeUIViewController` reports `prefersStatusBarHidden`, or the bundle needs a scene manifest —
  and each of those is a different fix. A key added because it might work is exactly what was just
  removed.
- **The rejected alternative is to go back to the letterbox.** The clock would return and two strips
  of every screen would stop being the brand's, which is the trade `B-94` measured and refused.
- **Not urgent.** No screen in this build puts anything at the top edge that the missing bar hides,
  and the screenshots this product is photographed from are taken on the JVM.

- AC: the cause is established and written down — which of the three it is, with what was run to tell
  them apart.
- AC: either the status bar is drawn, or the reason it cannot be is a line in
  `docs/services/operator-boundaries.md` alongside the other things a hand-assembled bundle gives up.
- Anchors: `scripts/ios-home-app.sh`, `client/src/iosMain/kotlin/io/konekt/client/ios/HomeEntryPoint.kt`.
