---
id: B-11
title: "The rollback screen states the reversal in money, not in apology"
status: done
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

- AC OK: the screen names the amount reversed, the date it moved, the order reference and the current
  balance. It is the first kompot screen in this build — a component tree assembled on the server from
  `banner`, `order_row` and `text`, answered through `respondKompotComponent`, and asserted to survive
  the wire root and all.
- AC OK: the row reads `$12 returned to balance on 28 Jun — nothing was activated.` The canvas's
  sentence, in the product's currency, with the date the **money** moved rather than the date the
  order was made.
- Anchors: `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt`,
  `shared/server-common/src/main/kotlin/io/konekt/money/DayFormat.kt`,
  `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreenTest.kt`.

## Where the numbers come from, and one sentence the canvas cannot have

**The amount is read from the ledger, not from the order's price.** They agree today. The ledger is
the record of what happened and the price is the record of what was asked for, and the day a partial
reversal exists only one of the two is still right. A test asserts which one the screen reads, which
is only possible to assert now — once a partial reversal exists, getting it wrong would be a defect
rather than a choice.

**"Your balance is back to where it was" is not on the screen.** The canvas writes it and the server
cannot promise it: between the reversal and the render the balance may have moved for an unrelated
reason — another purchase, a top-up — and the sentence would then be false while every number on the
screen was true. What is promised instead is two facts rather than a claim about their relationship:
what was returned, and what the balance is now. A test asserts the words are absent, because this is
exactly the kind of copy somebody later "improves" back.

**A purchase nobody confirmed has no provider to quote.** The banner says the confirmation window
passed rather than inventing a decline, and a rejected purchase says nothing was charged — because
nothing was held, so there is nothing to state in money at all. Three branches, three different true
things.

Dates are formatted on the server for the same reason money is, in **one zone, the operator's**. That
is a real limitation rather than a simplification: a traveller buying a roaming package at 23:00 their
own time may read a date a day off. Stated in `DayFormat` rather than discovered.

Background: [design-app-canvas](../design/design-app-canvas.md) sections 03 and 05.
