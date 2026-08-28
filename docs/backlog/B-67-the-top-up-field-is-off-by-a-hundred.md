---
id: B-67
title: "The top-up field reads minor units while every number printed beside it is major"
status: done
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

## What was done

**The unit is part of the type.** `TopUpAmount.Whole` / `TopUpAmount.Minor`, and
`StartTopUpUseCase.Params` takes one instead of a bare `Long` called `amountMinor`. The form route
says `TopUpAmount.whole(amount.long)` and the DTO route says `TopUpAmount.minor(request.amountMinor)`;
neither can be read as the other by accident.

**The conversion happens where the currency is known**, inside the use case, not at the edge. That is
not tidiness: the exponent belongs to the currency and the currency belongs to the ACCOUNT —
deliberately, so a request naming a different one is unrepresentable rather than validated. A route
multiplying by a hundred itself would reintroduce exactly the assumption the use case exists to
avoid, and would be right only while this product has one currency. `Money.ofMajor` already does it.

**And a test that starts from what somebody typed.** There was no form scenario in the stand at all —
`TopUpScenarioTest` posts the DTO endpoint, whose unit was never in doubt. `TopUpFormScenarioTest`
posts the form the way the client does, and its first assertion needs no constant of its own:

> the smallest amount the screen names is an amount the screen accepts

It reads the minimum out of the served limits line and types it. A label in one unit over a field in
another fails that by construction — and a test carrying its own copy of `MIN_MINOR` would have
agreed with the server about the number and still missed that the field could not express it. The
other side of the boundary is asserted too, so a server that accepts everything fails as well.

Measured both ways on a rebuilt stand. Without the fix the failure reads:

> the screen names 10 as its smallest top-up and then refused it

**One thing this does not fix, deliberately:** the field cannot express cents. kompot's amount input
filters keystrokes to digits, so $12.50 is untypeable. Every price in the catalogue and both limits
are whole dollars, so nothing is unreachable today; a plan priced in cents would be, and the fix
would be upstream rather than in this repository.

Found along the way and fixed with it: `Stand.topUp` converted with a hand-written `* 100`, which is
the exact thing `Money`'s own comment warns against. It goes through `Money.ofMajor` now.

## Anchors

| What | Where |
|---|---|
| The edge | `server/src/main/kotlin/io/konekt/topup/TopUpScreenRouting.kt` |
| The use case | `feature/purchase-server-domain/.../TopUpUseCases.kt` |
| The limits, in minor | `feature/purchase-server-domain/.../TopUpDomain.kt` (`TopUpLimits`) |
| The label, in major | `server/src/main/kotlin/io/konekt/topup/TopUpScreens.kt` (`limitsLine`) |
