---
id: B-68
title: "Five reasons a purchase is refused render as one sentence that names none of them"
status: open
priority: P1
size: M
stage: stage-m4-proof
epic: feature-purchase
---

# B-68 — The refusal a subscriber can act on offers nothing to act with

Buying a $12 plan on a $0 balance gives:

> This purchase could not be started, and nothing was charged.

…and a single **Back**. Not what went wrong, and no way to fix it.

`ValidatePurchaseInterceptor` refuses for five distinct reasons — plan not in the catalogue, plan no
longer on sale, price changed, no account, balance does not cover it — each with its own message,
under a comment stating that *"the saga ends REJECTED and the subscriber is told why"*. They are not
told why: `PurchaseResultScreen.rejected` prints a constant.

The type is even more explicit. `KonektException.InsufficientFunds` carries a `shortfall` and says
of itself:

> Its own case rather than a Conflict, because it is the one refusal a subscriber can act on and
> the screen offers them a top-up.

The screen offers no top-up, and the shortfall reaches no pixel.

## Why the reason is not merely ignored — it is not there

`rejected(order)` could consult `order.declineReason`, and it would get `null`. That field is read
out of a `decline` ledger row, and a VALIDATION reject writes none — only the payment step does.
On the contour, after a rejected purchase:

```
SELECT count(*) FROM ledger_entry WHERE kind='decline';  -->  0
```

`petiches` has no column for a reject message either, so the sentence the interceptor composed is
gone by the time any screen is built.

## There is already a right answer in this repository

`TopUpScreens.notAccepted` hits the identical wall and handles it properly: it does NOT consult
`declineReason` (its comment explains that a validation reject writes none), and instead repeats
**the thing the subscriber can act on** — the accepted range — and points back at the form they came
from. The purchase result screen should do the same for the reason that matters here: say the
balance and offer the top-up.

## Fix

Two parts, and the first is worth doing alone:

1. The screen states what can be acted on. It already receives the balance and the price, so
   "insufficient" is derivable at render time without any new persistence, and the control is a
   `navigate` to top-up.
2. If the other four reasons are to be distinguishable, the reject message needs somewhere to live —
   a `decline` ledger row written by the validation reject, which is the same shape the payment
   decline already uses.

## Anchors

| What | Where |
|---|---|
| The constant sentence | `feature/purchase-server-data/.../PurchaseResultScreen.kt` (`rejected`) |
| The five reasons | `feature/purchase-server-domain/.../PurchaseInterceptors.kt` |
| The promise on the type | `shared/domain/.../KonektException.kt` (`InsufficientFunds`) |
| The precedent that got it right | `server/src/main/kotlin/io/konekt/topup/TopUpScreens.kt` (`notAccepted`) |
