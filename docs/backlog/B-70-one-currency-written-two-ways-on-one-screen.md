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
[kompot#97](https://github.com/youndie/kompot/issues/97) — `amount_input` had a `currencySuffix` and
no way to say "before" — and konekt worked around it locally with a comment naming the issue.

**The issue is closed and the workaround is gone.** `0.33.1.93` added a `currencyPrefix` beside the
suffix, at most one set; the screen now fills whichever side the currency's own layout names, and the
symbol is out of the label. Both halves were checked in the artefact before the bump — a component
carrying a field and a renderer ignoring it are the same green build — and the guard below did not
change shape, only which field it looks at.

**The placement comes from the same table, not from this deployment.** `MoneyFormat` answers
`leadingSymbol` and `trailingSymbol`, exactly one of which is non-null for any currency — two
questions rather than one returning a side, so a caller cannot hold the answer and put it in the wrong
field. Nothing on the screen spells a symbol or picks a side.

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
