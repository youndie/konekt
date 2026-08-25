---
id: B-11
title: "The rollback screen states the reversal in money, not in apology"
status: open
priority: P1
size: S
stage: stage-m1-money
epic: feature-buy-package
blocked_by: [B-10]
---

# B-11 — The rollback screen states the reversal in money, not in apology

Canvas section 03, fourth frame: *"The provider declined the operation. We reversed the hold — your
balance is back to where it was, and no eSIM was issued."* Plus the reference to quote to support.
The designer's note beside it says what the frame is for — rollback stated in money: what was
reversed, what the balance is now, and the reference.

- **The decision and its reason.** The screen is rendered from the saga's compensated state, so the
  numbers on it come from the saga row rather than from the request that started it. A screen that
  re-derives the balance from a fresh read can show a balance that has since moved for another reason.
- The rejected alternative is a generic error screen with the reason in a subtitle. It is one screen
  fewer to build and it is the screen that generates the support call this one prevents.
- Not covered: partial compensation. Every step in the purchase saga compensates fully or the saga
  does not proceed.

- AC: after a refused payment the screen names the amount reversed, the resulting balance and the
  order reference, all read from the order.
- AC: the same order in history reads `450 ₽ returned to balance on 28 Jun — profile never activated`.
- Anchors: `server/src/main/kotlin/io/konekt/screens/PurchaseResultScreen.kt`.

Background: [design-app-canvas](../design/design-app-canvas.md) sections 03 and 05.
