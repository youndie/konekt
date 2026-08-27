---
id: B-54
title: "The eSIM install wizard has routes, a step machine and no screen that leads to it"
status: done
priority: P0
size: M
stage: stage-m3-product
epic: feature-esim
---

# B-54 — The third feature whose server half works and has no button

`esimWizardRoutes()` is installed, `EsimWizardStepAction` is registered on both sides, the step
machine is `wizard-core`'s and the QR component has a renderer. Nothing navigates to any of it. The
profile screen shows a COUNT of eSIMs held, as a sentence; the purchase result offers "Done"; the
home screen draws a roaming package as a counter card. A subscriber who buys a plan can never install
it from the product.

This is the same shape twice already fixed and worth naming as a shape rather than as three bugs:

| Feature | Server half | What was missing | Closed by |
|---|---|---|---|
| Confirming a purchase | saga suspends on `CONFIRM` | a button | B-49's follow-up |
| Topping up | saga, limits, compensation, routes | one form field | B-40's follow-up |
| Installing an eSIM | routes, step machine, QR renderer | somewhere to press | **this** |

`FeatureModulesReachTheGraphTest` cannot see it: the module IS in the graph and the routes ARE
installed. What is absent is a `navigate` in any served tree — which is data the server emits rather
than a binding a test can inspect.

- **The decision and its reason.** The entry point belongs on the **purchase result**, because that
  is where the canvas puts it: section 03's success frame is "Paid. eSIM is ready to install" with
  `Install eSIM` and `Later, show receipt`. Putting it only on the profile would be correct and
  useless — nobody opens Profile after paying.
- The roaming package on the home screen wants the same door, and the canvas draws it as its own row
  ("Turkey · 10 GB / eSIM ready to install / Install") rather than as a counter card. That half can
  follow; the purchase result is the one that makes the feature reachable at all.
- **Not covered:** a guard against the shape recurring. It is worth one — "every screen kind the
  server serves is the destination of some action in some other tree" is checkable against the
  route graph — and it is [B-56](B-56-unreachable-screen-guard.md) rather than a footnote here.
- AC: buying a plan and confirming it ends on a screen with a control that starts the wizard, and
  the wizard's first step draws.
- AC: the conformance walk reaches the wizard's screens, or the coverage list says in words why not.
- Anchors: `server/src/main/kotlin/io/konekt/Application.kt`,
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt`,
  `feature/esim-server-data/`.

## What landed

**The flow gained an address.** `GET /api/v1/screens/esim-install` opens the subscriber's run —
theirs if one is unfinished, a new one if not — and the purchase result's completed branch carries
`Install eSIM` pointing at it. Verified against the stand end to end: top up, buy, confirm, and the
result screen answers with the button; opening the screen twice returns the same `wizardId`.

**Why "open" and not "start", which is the part worth keeping.** The POST that already existed
creates a run per call, and a POST is not something a `navigate` can point at — the client fetches a
screen with a GET. So the address had to be a GET, and a GET may be repeated: a refresh, a return
from the background, a second press. `OpenEsimWizardUseCase` resumes, and `findUnfinishedBy` takes
the NEWEST row rather than `singleOrNull` — nothing stops two runs existing, and `singleOrNull` would
answer "none" for a subscriber who has two, which reads as "start a third".

**The first arrival still writes a row**, which is a GET with a side effect exactly once per install.
Named rather than hidden: the alternatives are a POST nothing can navigate to, or a screen that
refuses to exist until something POSTs — both put the entry point back where nothing could reach it.

**A finding the goldens produced.** The wizard's first step is a step meter, a paragraph and a button
on no surface, and a frame of it came out **5% opaque** — which `GoldenContentTest` cannot tell from a
capture that failed. It is photographed through the application frame instead, where the ground is
painted. The threshold was right; the screen really is that thin, and that is worth knowing about
the screen rather than about the guard.

**Not done here:** the roaming package on the home screen, still drawn as a counter card where the
canvas draws its own row with an `Install` of its own. The purchase result is what makes the feature
reachable at all; that half can follow.
