---
id: B-70
title: "The amount field writes the currency as a suffix while every figure beside it is a prefix"
status: done
priority: P2
size: S
stage: stage-m4-proof
epic: feature-balance
---

# B-70 — "50 $" six lines above "Between $10 and $50,000"

The top-up screen writes one currency two ways at once: the input renders **50 $** and the limits
line under it renders **$10**. Both come from the same server, in the same response.

`AmountInputComponent.currencySuffix` is a suffix by construction, and the server fills it with
`MoneyFormat.symbol(Currency.DEFAULT)`. `MoneyFormat` knows better than that: it holds a layout per
currency, and USD's is a prefix — which is why every other number in this product reads `$12`.

So the product already has one answer to "where does the symbol go", and the field cannot ask it.

## What was done

**Both halves, in the order this repository prescribes.** The gap went upstream as
[kompot#97](https://github.com/youndie/kompot/issues/97) — `amount_input` has a `currencySuffix` and
no way to say "before" — and konekt works around it locally with a comment naming the issue, to be
deleted when the field learns a side.

**The workaround is driven by the same table, not by this deployment.** `MoneyFormat.trailingSymbol`
answers with the symbol when the currency writes it after the amount and with **null** when it writes
it in front. Answering null rather than the symbol is the point: it makes "this cannot be drawn that
way" a case the caller must handle instead of a placement it can get wrong. The screen then uses the
field the toolkit has where it fits, and names the currency in the LABEL where it does not —
`Amount ($)`, which claims no position and stays visible once the label floats.

The item's own warning turned out to be about the majority rather than a future: **two of the five
currencies already in the table are symbol-first**, so neither half could be hard-coded away.

**The guard is over every currency, not over `Currency.DEFAULT`.** `AmountFieldPlacementTest` reads
how `MoneyFormat` writes an actual amount in each currency and requires the field to agree — with a
vacuity assertion that both branches were exercised, since a table that drifted to all-prefix or
all-suffix would pass while checking one. A test written about the deployment's own currency would
have agreed with the bug for the other two. Proved by mutation: making `trailingSymbol` answer for
every currency fails it, naming the currency.

**The recording was stale and the golden photographed the defect.** `top-up-screen.json` still held
`label: Amount, suffix: $`, so `Gallery_Top_up.png` showed "50 $" over "$10" and nothing objected.
Re-recorded from the stand and the goldens regenerated.

## Anchors

| What | Where |
|---|---|
| The field | `server/src/main/kotlin/io/konekt/topup/TopUpScreens.kt` |
| What the product knows about placement | `shared/server-common/.../MoneyFormat.kt` |
