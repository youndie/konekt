---
id: B-67
title: "The top-up field reads minor units while every number printed beside it is major"
status: open
priority: P0
size: S
stage: stage-m4-proof
epic: feature-balance
---

# B-67 — Typed "5 000 $", credited "$50"

On the test contour, with the field showing **5 000 $** and the line under it reading **Between $10
and $50,000**, the result screen said:

> **$50 added** — Balance: $50

Off by exactly one hundred. The field's number is taken as MINOR units; every other number on the
same screen is major.

The first attempt made it plainer: typing **50** — a number squarely inside the stated range — was
refused by a screen that then repeated the range that contains it.

> That amount was not accepted. Nothing was charged. Between $10 and $50,000.

50 minor units is $0.50, which is under the $10 minimum. The refusal is arithmetically correct and
reads as a contradiction, because the subscriber and the server are not talking about the same unit.

## Where the two units meet

`TopUpScreenRouting` passes `amount.long` — the number as typed — into
`StartTopUpUseCase(amountMinor = ...)`, which builds `Money(params.amountMinor, currency)`.
`TopUpLimits.MIN_MINOR = 1_000` / `MAX_MINOR = 5_000_000` are minor, and `limitsLine()` formats
those same constants through `MoneyFormat` for display — so the LABEL is right and the INPUT is not.

Nothing in the type system objects: both sides are a `Long`.

## Why the tests are green

Every test of this feature supplies the amount in minor units directly, because it is calling the
use case — which is the correct unit AT THAT BOUNDARY. The conversion that does not happen is the
one between the form field and the use case, and no test crosses it with a number a person typed.

## Fix

The boundary needs one conversion and one name that says which side it is on. Either the field
declares itself in minor units and the client renders accordingly, or — better, since the label,
the limits line and every other figure in this product are major — the route multiplies at the edge
and `Params` stops being called `amountMinor` by a caller handing it majors.

Then a test that starts from what somebody TYPED rather than from what the use case expects. The
stand is the right level: type an amount, assert the balance moved by that amount.

## Anchors

| What | Where |
|---|---|
| The edge | `server/src/main/kotlin/io/konekt/topup/TopUpScreenRouting.kt` |
| The use case | `feature/purchase-server-domain/.../TopUpUseCases.kt` |
| The limits, in minor | `feature/purchase-server-domain/.../TopUpDomain.kt` (`TopUpLimits`) |
| The label, in major | `server/src/main/kotlin/io/konekt/topup/TopUpScreens.kt` (`limitsLine`) |
