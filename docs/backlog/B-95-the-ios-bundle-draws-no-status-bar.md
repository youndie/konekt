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

## Five things it is not

Followed up the same evening, and every one of these was run rather than reasoned about. None fixed
it, and together they are most of the work: the item is now a short list of candidates rather than an
open question.

| Tried | Result | What it rules out |
|---|---|---|
| `UIStatusBarStyleDarkContent` + `UIViewControllerBasedStatusBarAppearance` false in the plist | no change | the plist route — that key is read through `UIApplication.statusBarStyle`, which modern iOS ignores |
| `xcrun simctl status_bar override --time 9:41` | no change | **nothing**, as it turned out: an override changes the bar's CONTENT, not whether it is drawn. It was read as "the bar is hidden" and that reading was wrong |
| instrumenting the delegate at launch | `windowScene=true appStatusBarHidden=false vcPrefersHidden=false connectedScenes=1` | all three original hypotheses at once: the window has a scene, a scene exists, the app does not think the bar is hidden, and the compose view controller does not ask for it hidden |
| a container `UIViewController` returning `.darkContent` from `preferredStatusBarStyle` | no change | the view-controller route, and with it "the clock is drawn in a colour that matches the ground" |
| — | — | the container was **reverted**: structure added on a falsified hypothesis, changing nothing observable, is what this item's first round already removed once |

So the app's own state is entirely consistent — it believes it has a visible status bar — and the
system draws none for it.

## What is left

**SpringBoard does not composite a bar for this bundle.** The one structural difference from an
Xcode-generated application still untested is the absence of `UIApplicationSceneManifest`: this bundle
runs on the legacy app-delegate lifecycle, and the simulator's log shows SpringBoard managing
status-bar settings per *scene* (`SBWindowSceneStatusBarSettingsAssertion`). Adding a manifest needs a
`UISceneDelegate`, which is a real change to the entry point rather than a plist line, and a bundle
that gets it wrong shows nothing at all — which is why it is written down rather than attempted at the
end of a long session.

## The original hypothesis, now refuted

It said the manually created `UIWindow` might never be attached to a window scene, since the status
bar belongs to the scene. **Instrumenting the delegate answered `windowScene=true`, so it is.**

Kept rather than deleted: "the window may never be attached" is exactly the sort of plausible sentence
that survives being wrong if nobody writes down that it was measured — and it is what produced the
instrumentation that refuted it.

- **The decision: establish the cause before changing anything.** Either the window needs a scene, or
  `ComposeUIViewController` reports `prefersStatusBarHidden`, or the bundle needs a scene manifest —
  and each of those is a different fix. A key added because it might work is exactly what was just
  removed.
- **The rejected alternative is to go back to the letterbox.** The clock would return and two strips
  of every screen would stop being the brand's, which is the trade `B-94` measured and refused.
- **Not urgent.** No screen in this build puts anything at the top edge that the missing bar hides,
  and the screenshots this product is photographed from are taken on the JVM.

- AC: the cause is established and written down. Five candidates are eliminated above; what remains is
  the scene manifest, and testing it means giving the entry point a `UISceneDelegate`.
- AC: either the status bar is drawn, or the reason it cannot be is a line in
  `docs/services/operator-boundaries.md` alongside the other things a hand-assembled bundle gives up.
- Anchors: `scripts/ios-home-app.sh`, `client/src/iosMain/kotlin/io/konekt/client/ios/HomeEntryPoint.kt`.
