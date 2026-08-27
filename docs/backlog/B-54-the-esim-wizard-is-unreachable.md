---
id: B-54
title: "The eSIM install wizard has routes, a step machine and no screen that leads to it"
status: open
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
