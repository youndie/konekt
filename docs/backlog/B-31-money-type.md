---
id: B-31
title: "Money is a type, and only the server formats it"
status: open
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

- AC: adding two `Money` of different currencies throws, asserted in a test.
- AC: a round-trip through the wire form of a DTO preserves the exact minor units for a zero-exponent
  and a three-exponent currency.
- AC: `grep` finds no formatting of an amount in the client module.
- Anchors: `shared/domain/src/commonMain/kotlin/io/konekt/domain/Money.kt`,
  `shared/domain/src/commonTest/kotlin/io/konekt/domain/MoneyTest.kt`.

Background: [research-stack](../research/research-stack.md) D15.
