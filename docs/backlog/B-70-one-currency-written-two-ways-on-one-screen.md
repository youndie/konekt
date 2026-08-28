---
id: B-70
title: "The amount field writes the currency as a suffix while every figure beside it is a prefix"
status: open
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

## Fix

Either the wire type gains the placement `MoneyFormat` already knows (an upstream ask on kompot's
forms, in the shape [research-upstream-proposals](../research/research-upstream-proposals.md)
records), or the screen stops using a suffix field for a prefix currency. The second is available
today and the first is the one that survives a second currency.

Worth deciding rather than patching: the moment `Currency` gains a suffix currency, a hard-coded
choice here becomes wrong for the other half of the table.

## Anchors

| What | Where |
|---|---|
| The field | `server/src/main/kotlin/io/konekt/topup/TopUpScreens.kt` |
| What the product knows about placement | `shared/server-common/.../MoneyFormat.kt` |
