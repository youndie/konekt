---
id: B-59
title: "The confirmation is a banner and a button; the canvas draws what is about to be spent"
status: open
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
