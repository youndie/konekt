---
id: B-40
title: "A subscriber is created with nothing and there is no way to add any"
status: done
priority: P1
size: M
stage: stage-m3-product
epic: feature-balance
---

# B-40 — A subscriber is created with nothing and there is no way to add any

`VerifyOtpUseCase` creates an account with `Money.zero(Currency.DEFAULT)`, and no route, saga or
consumer ever increases it. So the second most frequent scenario in the product analysis — "top up the
balance" — is unreachable, and the first purchase any real subscriber attempts is refused for
insufficient funds.

It surfaced building the end-to-end stand (`B-35`): every purchase scenario there begins with an
`UPDATE account SET balance_minor` issued straight at the stand's database, because there is nothing
to call.

- **The decision and its reason.** A top-up is a **saga**, not an endpoint that adds a number. It
  takes money from a provider that can refuse, and a balance raised before the provider confirmed is
  money the operator has given away — which is the same shape as the purchase saga and should reuse
  it rather than grow a second, simpler path that is wrong in a way nobody notices until a refund.
- The rejected alternative is a development-only endpoint that sets a balance. It is what the stand
  needs and nothing else, and a production surface that exists for a test tends to survive into a
  deployment.
- Not covered: real card details. The payment gateway is mocked at the system boundary, exactly as it
  is for a purchase, and its refuse switch is what makes the failing branch reachable.

- AC: a subscriber with an empty balance can top up and then buy, driven through the stand rather
  than through a database write.
- AC: a top-up the provider refuses leaves the balance exactly where it was, and says so in money.
- AC: `Stand.creditAccount` is deleted, and the scenarios use the product's own path.
- Anchors: `feature/purchase-server-domain/`, `e2e/src/test/kotlin/io/konekt/e2e/Stand.kt`.

Background: the product analysis lists "пополнить баланс (mock-платёж)" as the second scenario by
frequency; [research-architecture](../research/research-architecture.md) D10 is the refuse switch this
would reuse.

## What landed

A saga of three interceptors, the purchase saga pointed the other way, and pointing it the other way
is the only decision here that could not have gone differently.

| | Purchase | Top-up |
|---|---|---|
| the money is | already the subscriber's | the provider's until it settles |
| so the order is | hold, then settle | **settle, then credit** |
| and it waits | yes, for a confirmation | no |

A balance raised before the provider confirmed is money the operator has given away, and it is given
away in exactly the case the payment mock exists to produce. There is no confirmation step because a
top-up **is** the agreement — a purchase waits because the subscriber is agreeing to spend money they
already have — and adding one would cost two more saga rows per top-up to ask a question already
answered.

Limits live in the VALIDATION step, so a refusal by amount ends the saga `rejected` with nothing
compensated, while a refusal by the provider ends it `compensated`. Those are different words for
different things and the tests assert both.

**Two engines over one saga table now, named by saga type.** petich resolves nothing by type — an
engine is a fixed interceptor list — so an unqualified `get()` would have handed a top-up to the
purchase engine, which supports none of its steps, completes having done nothing, and reports success.
The sweeper's `engineFor` moved from `{ get() }` to a dispatch on the saga's type for the same reason:
rolling one type back with another's compensations runs the wrong ones, or none.

- AC MET: a subscriber with an empty balance tops up and then buys, through the stand.
  `TopUpScenarioTest` plus `TopUpSagaTest` against a real Postgres.
- AC MET: a refused top-up leaves the balance exactly where it was and says why. Asserted against the
  balance text from **before**, character for character — not merely "not the raised one".
- AC MET: `Stand.creditAccount` is deleted. All four existing scenarios and the conformance walk now
  begin by topping up through the product, so a break in it fails five tests rather than none.
  The walk gained a second templated address as a side effect: `GET /api/v1/top-ups/{topUpId}` is
  reachable because there is now a top-up to name.

## Two defects found on the way, neither of them this item's

**Every consumption taking more than half of what was left zeroed the remainder.**
`ExposedUsageCounters.consume` applies a subtract and a clamp as two statements, and they were in the
order that makes them **not** mutually exclusive: the subtract changes the row, and the clamp's
predicate then reads the new value — 1000 less 950 left 50, and 50 < 950 zeroed it. No negative
number, no exception, no log. `revokePlanAllowance` had the same shape twice, where it meant a
compensated purchase revoking the allowance a **different** purchase had paid for. Both fixed by
putting the clamp first, both covered at the boundary in `UsageCounterClampTest`, both proved by
putting the defect back.

It was covered. The test could not run — see [B-42](B-42-tests-that-cannot-run.md), which is the item
for the check that would have said so.

**The order status vocabulary disagrees with itself** — [B-41](B-41-order-status-vocabulary-disagrees.md).
Found because a top-up refused by the limits answers `rejected`, and there is no constant for it.

## Not covered

- **A top-up does not appear in the operation history.** `ExposedHistoryRepository` reads
  `EntitlementTable`, which is purchases; a history that lists what a subscriber spent and not what
  they put in is half a history. It is a query change rather than a schema one, and it belongs with
  whatever decides how the two are ordered against each other.
- **Real card details.** The gateway is mocked at the system boundary exactly as it is for a purchase,
  and its refuse switch is what makes the failing branch reachable at all.
- **`CollectFundsInterceptor.compensate` has no caller that ever runs.** The only step after the credit
  announces and cannot fail. It is written and tested directly, because the day a step is added
  between them the failure is a subscriber holding money the operator was never paid for, and nothing
  would have objected.
