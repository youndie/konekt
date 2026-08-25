---
id: screen-purchase-result
title: Purchase result — including the rollback
type: client_screen
platform: [jvm]
status: active
entry:
  jvm: "GET /api/v1/screens/orders/{orderId} — a server-built tree; there is no client-side screen class"
parent_feature: feature-plan-purchase
calls_api:
  - endpoint-purchase
source: feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt
---

# Screen: purchase result

> The screen this product was worth building for: the **compensated** branch. A rollback is stated in
> money — what was reversed, and what the balance is now — rather than as an apology. A subscriber
> who can reconcile a reversal against their bank does not ring support; one who is told "something
> went wrong" does.
>
> Read out of the source on 2026-08-25.

## 0a. Code anchors

| What | File |
|---|---|
| The tree | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt` |
| The route | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseRouting.kt` |
| Everything the screen needs, in one call | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseUseCases.kt` — `LoadOrderScreenUseCase` |
| The wire type of a row | `shared/components/src/commonMain/kotlin/io/konekt/components/OrderRowComponent.kt` |
| Money and dates | `shared/server-common/src/main/kotlin/io/konekt/money/MoneyFormat.kt`, `.../DayFormat.kt` |
| Tests | `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreenTest.kt` |

## 0. Entry point and visibility

- **Entry point:** `GET /api/v1/screens/orders/{orderId}`, after `POST /api/v1/purchases` or its
  confirmation has answered with an order id.
- **Shown when:** the caller holds a valid token **and the order is theirs**. Somebody else's order
  answers `404` — never `403`, which would confirm the order exists.

## 1. Screen states

The root is a `column` with id `purchase-result`, and the branch is `order.status`:

- [x] **`compensated`** — a `banner` (`purchase-reversed`, tone `error`), an `order_row`
  (`order-<orderId>`), and a `text` (`balance-now`).
- [x] **`completed`** — a `banner` (`purchase-completed`, tone `info`) — "*<plan> is active.*" — and an
  `order_row` with status `completed`, statusText "Paid".
- [x] **`rejected`** — a single `banner` (`purchase-rejected`, tone `error`): "This purchase could not
  be started, and nothing was charged." **No order row and no money**, because nothing was held: that
  absence is the difference between this state and the one above it.
- [x] **In flight** (`pending`, `awaiting_confirmation`, `compensating`) — a `banner`
  (`purchase-in-flight`, tone `info`): "Confirming with the payment provider. Keep the app open —
  this usually takes under 15 seconds."
- [ ] **Loading / error:** not built. The route either answers a tree or a `KonektException` mapped by
  `StatusPages`.

## 2. API integration

| Call | Contract | Endpoint document |
| :--- | :--- | :--- |
| `GET /api/v1/screens/orders/{orderId}` | `OrderScreen` | [endpoint-purchase](../api/endpoint-purchase.md) |
| `POST /api/v1/purchases/{orderId}/confirm` | `Purchases.ById.Confirm` | [endpoint-purchase](../api/endpoint-purchase.md) |

## 3. Initialisation

**Input parameters:**

| Parameter | Type | Description |
| :--- | :--- | :--- |
| `orderId` | `String` | the saga id; there is no second table holding an order |

One call loads everything: the order, the reversal from the ledger and the current balance. A screen
that made three lookups of its own would be a screen deciding what a purchase is, and the second
screen to want the same thing would decide it again, slightly differently.

## 4. UI elements, top to bottom

### 4.1. The banner, on the compensated branch

- **Text:** the provider's own decline reason when there is one — the mock's is "The provider declined
  the operation." — otherwise "The confirmation window passed, so the purchase was not completed."
- **Why the reason is stored at all:** petich carries a `Compensate` reason to its metrics and does
  not persist one, and the compensating step (the hold) has no way to know why it is being undone. So
  the settling step writes it into the ledger where the screen can read it back.

### 4.2. The order row

- **`reference`:** `orderId.take(8)` — short enough to read aloud to support, which is the whole
  reason it is on the screen.
- **`amountText`:** `MoneyFormat.format(returned, signed = true)`.
- **`returned` comes from the ledger, not from the order's price.** They agree today. The ledger is
  the record of what happened and the price is the record of what was asked for, and the day a
  partial reversal exists only one of the two is still right. `PurchaseResultScreenTest` has a case
  where they deliberately differ.
- **`dateText`:** the day the money moved, from the reversal — not the day the order was made.
- **`noteText`:** "*$12 returned to balance on 28 Jun — nothing was activated.*"

### 4.3. "Your balance is now …"

- **This is deliberately not the canvas's sentence.** The canvas writes "your balance is back to
  where it was", and the server cannot promise that: between the reversal and this render the balance
  may have moved for an unrelated reason, and the sentence would be false while every number on the
  screen was true. What can be promised is what was returned and what the balance is now — two facts
  rather than a claim about their relationship.
- **Omitted entirely when the balance could not be read.**

## 5. Navigation (summary)

Nothing on this screen navigates. It carries no action of any kind — no "try again", no "contact
support".

## 6. Quirks

- **`compensated` is not `failed`.** petich ends a cleanly rolled-back saga in `FAILED`, and showing a
  subscriber "failed" would be wrong twice over: nothing failed from their side, and the hold was
  reversed. A compensation that itself failed does **not** reach `FAILED` — it stays `COMPENSATING` —
  so the word is unambiguous inside petich and merely unfortunate outside it.
- **`compensating` is drawn as "in flight", and it is also the state that needs a person.** A
  compensating step that itself failed sits here. Nothing on the screen distinguishes the two.
- **The only ways to reach the compensated branch** are the second stand server, whose payment mock
  refuses, and abandoning a confirmation for five minutes. That is why the compose file runs two
  servers rather than offering a switch: the mode is read once at startup.
- **The rejected branch says "nothing was charged" and means it** — a validation `Reject` runs before
  the hold, so there is nothing to reverse and nothing to state in money.
