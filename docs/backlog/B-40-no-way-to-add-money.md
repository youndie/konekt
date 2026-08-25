---
id: B-40
title: "A subscriber is created with nothing and there is no way to add any"
status: open
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
