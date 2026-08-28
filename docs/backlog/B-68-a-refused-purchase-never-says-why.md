---
id: B-68
title: "Five reasons a purchase is refused render as one sentence that names none of them"
status: done
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

## What was done

Both parts, because the first alone would leave four of the five still unsayable.

**A code, not a sentence.** `PurchaseRefusals` holds the five words; the interceptor writes one into
the `decline` ledger row and the screen composes the copy — the same split `EsimRefusal` makes, and
the same rule every other string in this product follows. A sentence stored in the ledger would be
copy edited by changing a row, and a reason the screen cannot branch on cannot grow the control that
goes with it.

**The column carries two things and the STATUS says which.** A `decline` note is one of our codes on a
REJECTED order and the provider's own words on a COMPENSATED one. The two cannot overlap — a
validation refusal ends the saga before any provider is called — and `PurchaseResultScreen` switches
on the status first, so each branch reads the column for exactly one meaning. Written down where the
codes are declared.

**The account is resolved first.** It used to be last, and every refusal before it had nowhere to
write its reason. Which refusal wins when several apply therefore changed, and only between states
this product should not be able to reach.

**The control matches the reason.** Short of money → `Top up`; the plan moved → `See plans`; nothing
to act on → the way out alone, drawn as the primary, which it then correctly is. And the money branch
names BOTH numbers, because "you do not have enough" is a sentence somebody has to do arithmetic on
before they know what to type into the top-up field.

**Guarded at four levels, each answering something the others cannot.**

| Level | What it says |
|---|---|
| `PurchaseResultScreenTest` | the five sentences differ, by comparing them rather than matching each — a regression to one constant satisfies any number of one-at-a-time `contains` assertions |
| `PurchaseSagaTest` | the code is actually written, through a real database, and writing it moves no money |
| `PurchaseRefusalScenarioTest` | the assembled product answers it — a test per seam cannot see a chain that delivers nothing |
| `AppFrame - App purchase refused` | somebody looks at it |

The last one is the gap that let this happen: the gallery photographed the COMPLETED order and never
the refusal, and a state nobody looks at is a state whose copy a green suite can rewrite. It is also
the frame a first-time subscriber gets by pressing the first thing they see.

`ClientAgainstStandTest` was waiting on the old constant and failed, which is the right way to find
out that copy changed; it now asserts the new sentence and that `Top up` is on the screen.

## Anchors

| What | Where |
|---|---|
| The constant sentence | `feature/purchase-server-data/.../PurchaseResultScreen.kt` (`rejected`) |
| The five reasons | `feature/purchase-server-domain/.../PurchaseInterceptors.kt` |
| The promise on the type | `shared/domain/.../KonektException.kt` (`InsufficientFunds`) |
| The precedent that got it right | `server/src/main/kotlin/io/konekt/topup/TopUpScreens.kt` (`notAccepted`) |
