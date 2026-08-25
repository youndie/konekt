---
id: feature-plan-purchase
title: Buying a plan — a saga with a confirmation, and a rollback stated in money
type: feature
status: active
owner: unassigned
involved_services:
  - konekt-server
  - konekt-broker
client_entries:
  - screen-purchase-result
  - screen-order-history
api:
  - endpoint-purchase
tags: [purchase, saga, petich, money, outbox]
---

# Buying a plan

## 1. Overview

A subscriber buys a data plan. The money is held, the subscriber confirms, the provider settles, the
package is activated and the allowance lands on their counters. Any step can refuse, and when one
does the whole thing is unwound — **and the unwinding is a screen the subscriber can reconcile**, not
an apology.

It is a saga rather than a transaction because one of its steps is a person: the confirmation may
take a minute, and nothing may hold a database connection or a thread while it does. petich is the
engine; the saga id **is** the order id, and there is no second table holding an order beside the
saga that already records every step it took.

## 2. Business rules

* **Four interceptors, not six**, and the number is measured rather than aesthetic: petich writes the
  saga row at every step boundary, about 9 writes for four steps against about 17 for six (petich's
  own figure, through `pg_stat_user_tables`; **not re-measured here**). This is the most frequent
  operation in the product.
* A purchase is refused **before anything happens** when the plan is unknown, off sale, priced
  differently from what the subscriber was shown, or beyond their balance. That refusal is a
  `Reject`: the saga ends `rejected` and there is nothing to undo.
* **A hold debits the visible balance.** This product shows one number, not an available-versus-total
  pair.
* The hold refuses **in the database**, not after a read: two purchases started together both pass a
  read-then-check, and what they would overspend is real money.
* The confirmation has a **five-minute deadline**, which is this step's and not the engine's. It is
  the same order as the one-time code such a confirmation usually involves, and it bounds how long a
  subscriber's own money sits held on a purchase they walked away from.
* The price and the plan's size are recorded **on the payload**, not re-read at settlement: the
  payload is what was agreed, and a catalogue that moved in between would grant an allowance nobody
  was shown.
* Settling and provisioning are **one step**. Splitting them would buy a rollback point between a
  captured payment and an inactive package, which is a state nobody wants to be able to reach.
* A rollback returns the money **and** takes the allowance back. Money that comes back while the
  gigabytes stay is a rollback that costs the operator rather than nobody.
* Every completion and every reversal is announced through the outbox, **inside the transaction that
  changes the state** — so "the work happened but nobody was told" is structurally impossible.
* History contains **everything that moved money**, and nothing else.

## 3. Flow

The four interceptors, in petich's phase order
(`feature/purchase-server-domain/.../PurchaseInterceptors.kt`):

1. **VALIDATION** — `ValidatePurchaseInterceptor`. Catalogue, sale state, price and balance. A refusal
   here is a `Reject`.
2. **AUTHORIZATION** — `HoldFundsInterceptor`. Holds the money, creates a pending entitlement, and
   returns `Suspend(requiredAction = "CONFIRM", ttl = 5 minutes)`. The saga stops, holding nothing.
   **An interceptor that returned `Suspend` is not re-executed on resume**, so the money is held once.
3. *(the subscriber confirms — `POST /api/v1/purchases/{orderId}/confirm`)*
4. **EXECUTION** — `ProvisionInterceptor`. Settles with the provider; on a decline it records the
   reason in the ledger and returns `Compensate`. On approval it captures the hold, activates the
   entitlement and **grants the allowance** through the usage feature's port.
5. **POST_PROCESSING** — `AnnouncePurchaseInterceptor`. Emits `purchase.completed` into the outbox.

Compensation walks back only through steps that **actually ran forward**, which is why the reversal
event is announced by the hold step and not by the announcing step: a purchase abandoned at the
confirmation never reaches POST_PROCESSING.

The outbox relay then publishes to [konekt-broker](../services/konekt-broker.md), topic `orders`,
keyed by order id.

## 4. Code anchors

| Service | Code |
|---|---|
| konekt-server | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/` — the four interceptors, the use cases, the ports, the history |
| konekt-server | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/` — the routes, the two screens, the ledger, the payment mock, the catalogue |
| konekt-server | `feature/purchase-shared-api/src/commonMain/kotlin/io/konekt/feature/purchase/shared/api/PurchaseApi.kt` — the contract |
| konekt-server | `server/src/main/kotlin/io/konekt/Application.kt` — `petichModule`: the engine, the phase timeout, `requireOutbox = true` |
| konekt-broker | `server/src/main/kotlin/io/konekt/events/BooblikOutboxPublisher.kt` — the transport petich does not provide |

## 5. Scenarios (BDD / test cases)

### Scenario: a confirmed purchase debits the account, activates the package and completes
* **Given:** a subscriber with enough balance and a plan on sale
* **When:** they start a purchase and confirm it
* **Then:** the order is `completed`, the balance is down by the price, and the entitlement is active
* **Automated:** `PurchaseSagaTest`

### Scenario: the allowance lands on the counters, end to end
* **Given:** the stand, with a real Postgres and a real broker
* **When:** a purchase for a 10 GB plan is confirmed
* **Then:** the home screen shows a data counter that did not exist before
* **Automated:** `PurchaseScenarioTest` — the case `a purchase that is confirmed completes, and the allowance lands`

### Scenario: a purchase nobody confirms is rolled back and the balance returns
* **Given:** a purchase suspended at its confirmation
* **When:** five minutes pass and the sweeper runs
* **Then:** the order is `compensated` and the balance is exactly what it was
* **Automated:** `PurchaseSagaTest`, and against a moved clock `SuspendedSagaExpiryTest`

### Scenario: a plan that is not on sale is refused with nothing to undo
* **Given:** the sold-out plan in the catalogue
* **When:** a purchase is started for it
* **Then:** the order is `rejected`, no hold was taken, and no compensation ran
* **Automated:** `PurchaseSagaTest`

### Scenario: a purchase beyond the balance is refused and the balance is untouched
* **Given:** a subscriber whose balance does not cover the plan
* **When:** they start a purchase
* **Then:** the order is `rejected` and the balance has not moved
* **Automated:** `PurchaseSagaTest`

### Scenario: a declined provider rolls the purchase back and the screen says why
* **Given:** the payment mock set to decline
* **When:** a purchase is confirmed
* **Then:** the order is `compensated`, the balance is back, and the screen carries the provider's own
  sentence — "The provider declined the operation." — with the amount and the reference
* **Automated:** `PaymentDeclineTest`, and on the stand `PurchaseScenarioTest` — the case
  `a purchase the provider refuses is rolled back, and the screen says so in money`

### Scenario: the decline reason survives to be read again
* **Given:** a purchase that was declined
* **When:** the order is fetched later
* **Then:** the reason is still there — it is written into the ledger by the step that learned it,
  because petich carries a `Compensate` reason to its metrics and does not persist one
* **Automated:** `PaymentDeclineTest`

### Scenario: a slow provider still completes
* **Given:** the payment mock with a delay
* **When:** a purchase is confirmed
* **Then:** it completes, and the run actually took the time — the EXECUTION phase timeout is 30 s,
  raised from petich's default 10 s because the screen promises "under 15 seconds"
* **Automated:** `PaymentDeclineTest` — the case `a slow provider still completes and actually took the time`

### Scenario: somebody else's order cannot be confirmed and does not admit to existing
* **Given:** an order belonging to another subscriber
* **When:** it is confirmed, fetched, or opened as a screen
* **Then:** the answer is `404`, never `403`
* **Automated:** `PurchaseSagaTest`

### Scenario: the completion reaches the broker, and a redelivery is recognisable
* **Given:** a completed purchase with a row in the outbox
* **When:** the relay runs
* **Then:** the event arrives on `orders`; a broker that is down delays the row rather than losing it,
  and a redelivered event carries the same id `<orderId>:purchase.completed`
* **Automated:** `OutboxRelayTest`

### Scenario: the order appears in the history it belongs to, and not in anyone else's
* **Given:** two subscribers with purchases
* **When:** each opens history
* **Then:** each sees only their own, newest first, with reversal lines where money came back
* **Automated:** `PurchaseScenarioTest`, `HistoryPagingTest`

### Scenario: walking every page visits each order exactly once and stops
* **Given:** more orders than a page holds
* **When:** the client follows `loadMoreAction` to the end
* **Then:** no order is seen twice or skipped, and the last page carries no next action
* **Automated:** `HistoryPagingTest`

### Scenario: a purchase the subscriber can pay for is refused because the balance is empty and cannot be filled
* **Given:** any newly created subscriber
* **When:** they try to buy anything at all
* **Then:** it is `rejected` — a subscriber is created with zero and **nothing in the product adds
  money**. Manual, and it is `B-40`. The e2e stand works around it with an `UPDATE` in SQL.

## 6. Out of scope

* Adding money to an account (`B-40`), and any real payment provider: no card is ever touched.
* A real plan catalogue with prices that move and a zone per plan (`B-19`). Three plans are in
  memory, one of them deliberately sold out because that is the fixture the refusal path needs.
* Changing a tariff (`B-21`), which is its own saga.
* Buying the add-on that the low counter card offers. The offer is copy; nothing sells one.

## 7. Quirks

- **petich's `FAILED` is the product's `compensated`.** A cleanly rolled-back saga ends in `FAILED`,
  and showing a subscriber "failed" would be wrong twice over. A compensation that itself failed does
  not reach `FAILED` — it stays `COMPENSATING`.
- **`202`, not `201`.** The usual answer is a saga waiting for a confirmation.
- **A saga test uses `runBlocking`, never `runTest`.** The virtual clock skips time forward for a
  suspended coroutine and the engine wraps every interceptor in `withTimeout`, so the first real
  database call inside a step jumps past the phase timeout, the step is cancelled and the saga
  compensates. petich swallows the cancellation into the compensation, so nothing is logged and what
  you see is a saga that rolled itself back for no reason.
- **`requireOutbox = true` is set explicitly, and `B-09` exists because of what happens without it.**
  petich degrades quietly to a plain update when handed a repository that cannot store events: the
  saga completes with correct state, every natural assertion passes, and nobody downstream is ever
  told.
- **The reversal is announced by the step being undone.** Putting it on the announcing step was a
  mistake a test caught: compensation only walks back through steps that ran, and the abandoned-
  confirmation case never reaches POST_PROCESSING — so the announcement would never fire for the one
  case it exists for.
- **The compensating step releases only what it took.** Every step can see everything, which makes
  "return the money twice" an easy mistake; the hold is the previous step's to release.
- **The event id is `<orderId>:<type>`, and the partition key is the order id read out of the
  payload.** The outbox row is `(id, type, payload)` and nothing else, so the key has to come from
  what is already there; a payload without an `orderId` is published unkeyed and round-robins.
- **The saga's storage format depends on the application's `Json`.** `@SerialName("purchase")` is
  load-bearing: without it the discriminator is the fully qualified class name, and moving a module
  would make already-persisted sagas unreadable.
- **`InsufficientFunds` exists in the error hierarchy and this feature never throws it.** The balance
  check is a saga `Reject`, not an exception, so the `409` that type maps to is unreachable on this
  path.
