---
id: B-59
title: "The confirmation is a banner and a button; the canvas draws what is about to be spent"
status: done
priority: P2
size: S
stage: stage-m3-product
epic: feature-buy-package
---

# B-59 — What a subscriber agrees to, before they agree to it

`PurchaseResultScreen.awaitingConfirmation` draws one banner — *"<plan> is ready for <price>. Confirm
to complete the purchase — nothing has been charged yet."* — and a button. Section 03's confirm frame
is a small table: **Plan**, **Price**, **Pay from** (`Balance · 2 480,50 ₽`, "Instant, no fee", with
a card as the alternative), a consent checkbox, then `Pay 1 190 ₽`.

Two of those four are buildable today and two are not:

- **Plan and Price as rows** rather than a sentence: the same facts, laid out so they can be read
  rather than parsed. Free — the data is already on `OrderView.payload`.
- **"Pay from: Balance · $X"** — the balance is readable on this screen already (the reversed branch
  reads it), and stating WHERE the money comes from is the one thing a confirmation is for. Cheap.
- **The card alternative** needs payment methods, which do not exist. Refused as a row: a screen
  offering a card this product cannot charge is worse than a screen that does not offer one.
- **The consent checkbox** is `checkbox_input` plus one field on a schema — mechanically free, and
  what it SAYS is a legal decision with no copy on the canvas. Same standing as the login consent
  box in [B-50](B-50-login-frame-six.md): the mechanism is not the blocker.

- **The decision and its reason.** Take the two that are data. A confirmation screen exists so the
  amount and its source are visible at the moment of agreeing; a banner makes them a sentence to
  parse, and the number that matters is the one people skim past.
- **Not covered:** the processing frame's `Cancel`, which would need the saga to accept an abort.
- AC: the confirmation names the price and the balance it will come out of, both formatted by the
  server, and neither is drawn when the server could not read it.
- Anchors:
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt`.

## What landed

The two halves that were data: **Plan** and **Price** as rows, and **Pay from** naming the balance.
The card alternative stays refused — offering one this product cannot charge is worse than offering
none — and the consent checkbox stays where [B-50](B-50-login-frame-six.md) left it: the mechanism is
free, the copy is a legal decision nobody has taken.

The price is also on the control: `Pay $15`. Somebody who reads only the button they are about to
press still reads the amount.

## The first live run contradicted itself, and the fix is the finding

The screen said *"Nothing has been charged yet"* beside **a balance that had already dropped by the
price**. Both sentences were true: `hold` decrements the account in the same statement it checks it
against, so at this point the money has left the available balance and simply has not been captured.
Together they read as an error the subscriber would report.

The old banner got away with it only because it showed no balance to contradict. Surfacing the number
is what made the copy's imprecision visible — which is the ordinary way a screen's words are tested.

So the screen says what a hold IS: *"$15 is on hold and has not been charged. Let the window pass and
it is released."* And the row reads **"Balance — $35 left after this"** rather than "Balance · $35",
because the figure is already net of the hold and a bare balance beside a price invites subtracting
twice.

## The branch had no test at all

Its whole copy could be rewritten and the file stayed green — which is how the gap was found: the
rewrite passed, and a rewrite passing is the same evidence as a mutation surviving. It is the branch
the confirm button was built for. Three cases now: the facts, the amount on the control, and a
balance the server could not read being left out rather than drawn as zero.

**One of those assertions failed the first time it was written**, and for the reason worth keeping:
`BannerComponent` is konekt's own type with its own `text` field, so `filterIsInstance<TextComponent>`
walks straight past it. The test reads the banner as a banner now.

## And one thing `B-58` unlocked the same day

`Not now` was drawn as a primary button beside `Pay $15` — two equal-looking answers to one question,
which is exactly what `ButtonEmphasis.QUIET`'s own comment warns about. It could not be acted on until
`quiet` had a look of its own, which it had never had.
