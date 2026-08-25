---
id: B-31
title: "Money is a type, and only the server formats it"
status: done
priority: P0
size: S
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-31 — Money is a type, and only the server formats it

Every screen in the canvas carries an amount — balance, plan price, top-up, the reversal line on a
compensated order — and the domain has one currency per account and a saga that moves money in four
steps. The nearest prior art on this stack had no money type at all: a free
`formatMoney(minorUnits: Long, currency: String)` written **twice**, once on the server for a screen
and once in a client view model, each dividing by a hard-coded `100`
([research-stack](../research/research-stack.md) §1.4).

- **The decision and its reason.** A `Money` value type in the shared domain carrying minor units and
  a currency, with the exponent belonging to the currency rather than assumed to be two. A hundred is
  right for the rouble and wrong for the yen, and nothing in a `Long` says which one it is holding.
  Arithmetic lives on the type; `Double` never touches money.
- **Backend-driven UI removes the second copy by construction.** The server builds the screen, so the
  server formats and the client renders a `text`. A client that cannot format money cannot format it
  inconsistently — which is why the formatter is server-side and there is exactly one.
- Its wire form is fixed once, here: minor units plus an ISO code. Never a pre-formatted string —
  that is unusable for arithmetic on the other side and invites a client to re-parse its own display.
- The rejected alternative is `BigDecimal`. It is correct and it serialises badly, compares by scale in
  a way that surprises, and hands the rounding decision to whoever writes the next `divide` call.
- Not covered: multi-currency arithmetic. Adding two `Money` of different currencies throws; a
  conversion is a domain operation with a rate, not an operator.

- AC ✅: `+`, `-` and `<` across two currencies all throw, and the message names both currencies
  rather than saying "mismatch".
- AC ✅: the wire round trip is asserted per currency at three exponents — `RUB` (2), `JPY` (0) and
  `KWD` (3) — not once. A round trip that only ever sees two-decimal money proves nothing about the
  two cases a hard-coded hundred gets wrong, and those are the whole reason for the type.
- AC ✅ **structurally rather than by grep**: `MoneyFormat` lives in `:server`, and no client module
  depends on `:server`. A client cannot format an amount because the code is not on its classpath —
  a rule enforced by where the code lives outlasts a rule enforced by review. The grep is redundant
  and will not be written; if a client module ever gains a dependency that would break this, it is a
  build-file change, which is visible.
- Also done: the exact overflow behaviour. A `Long` of minor units overflows past ninety quadrillion
  roubles, so this is insurance rather than a scenario — but a wrapped balance reports a positive
  number for a negative one, and no test about a business rule would catch that.
- Also done: `MoneyFormat` reproduces the canvas strings character for character, including the rule
  that a whole amount drops its zero fraction (`1 190 ₽`, not `1 190,00 ₽`). The spaces are
  non-breaking where the design's HTML has plain ones — the rendered result is identical until a line
  break falls between the thousands, and there it is the difference between an amount and a puzzle.
- Anchors: `shared/domain/src/commonMain/kotlin/io/konekt/domain/Money.kt`,
  `shared/domain/src/commonMain/kotlin/io/konekt/domain/Currency.kt`,
  `shared/domain/src/commonTest/kotlin/io/konekt/domain/MoneyTest.kt`,
  `server/src/main/kotlin/io/konekt/money/MoneyFormat.kt`,
  `server/src/test/kotlin/io/konekt/money/MoneyFormatTest.kt`.

**Amended 2026-08-25**: the product currency is `Currency.DEFAULT` = **USD**, and `MoneyFormat` gained
a per-currency layout — symbol placement, group and decimal separators, and whether a space sits
before the symbol. `$1,190.50` and `1 190,50 ₽` are the same amount written by two sets of rules, and
neither is a property of the amount. The canvas is drawn in roubles and now differs from the running
product in the shape of every amount; that is recorded in
[design-app-canvas](../design/design-app-canvas.md) rather than reconciled by pretending.

Not `java.text.NumberFormat`, though this module is JVM-only and could: its output is CLDR data that
moves between JDK releases — the space character in a currency format has changed more than once — so
a test asserting the exact string a subscriber reads would break on a toolchain upgrade with no change
here.

## The decision worth not re-litigating

`Currency` is a **closed enum**, which is the opposite of the rule the component dictionary follows,
and deliberately. There, every enum-shaped field is an open string because a client meeting an
unfamiliar word draws the neutral form and loses a colour. Here it cannot: an unknown currency has an
unknown exponent, so a client accepting it could not format it, round it or add it to anything.
Refusing is the only correct behaviour and an enum makes refusing the default.

The price is a row in the operator material (`B-30`): a currency the operator adds is a client
release. For a single-operator product whose currency set is known at build time, that is the right
side of the trade.

Background: [research-stack](../research/research-stack.md) D15.
