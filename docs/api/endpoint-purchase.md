---
id: endpoint-purchase
title: Purchases, the order screen and history
type: api_endpoints
status: active
services:
  - konekt-server
contract_source:
  - konekt:feature/purchase-shared-api PurchaseApi.kt (Purchases, OrderScreen, HistoryScreenResource)
parent_feature: feature-plan-purchase
---

# API: purchases, the order screen and history

> The **complete** route reference for this feature. URL shapes and bodies live in
> `feature/purchase-shared-api/src/commonMain/kotlin/io/konekt/feature/purchase/shared/api/PurchaseApi.kt`.
> The generated schema is [`openapi.json`](openapi.json), built from the routes and compared by the
> build (`B-23`).
>
> Read out of the source on 2026-08-25. Two of the six routes answer a **component tree** rather than
> a DTO, and that is stated per route below rather than left to be discovered.

## Routes — all of them, no exceptions

| Method and path | Auth tier | Answers | Purpose |
|---|---|---|---|
| `POST /api/v1/purchases` | **user token** | `202` + `PurchaseOrderResponse` | start a purchase saga for a plan |
| `POST /api/v1/purchases/{orderId}/confirm` | **user token** | `200` + `PurchaseOrderResponse` | answer the confirmation the saga is waiting for |
| `GET /api/v1/purchases/{orderId}` | **user token** | `200` + `PurchaseOrderResponse` | the order as data |
| `GET /api/v1/screens/orders/{orderId}` | **user token** | `200` + a **component tree** | the order as a screen — see [screen-purchase-result](../screens/screen-purchase-result.md) |
| `GET /api/v1/screens/history` | **user token** | `200` + a **component tree** | see [screen-order-history](../screens/screen-order-history.md) |
| `GET /api/v1/screens/history/page?cursor=…` | **user token** | `200` + `KompotPageResponse` | the next page of rows to append |

All six are mounted by `purchaseRoutes()`, which sits in the `AuthTier.USER` group of `konektRoutes`
in `server/src/main/kotlin/io/konekt/Application.kt`. **`authenticate` proves the caller is somebody and says nothing about whose
order this is** — the owner check is in the use case, beside the subscriber id, and it answers **404
and not 403**, because a 403 confirms the order exists and hands out an enumeration oracle.

**Why `202` and not `201`.** The usual answer is a saga waiting for a confirmation. Telling a client
the resource is created when the money has only been held is a lie it would have to unlearn.
Asserted in `PurchaseScenarioTest`, on the stand.

**Why the screen and the order are separate routes.** One is the order's state as data, the other is
what to draw; and the page route is separate from the history screen for the same reason — the client
appends items to a list it already has, and sending it a screen would replace one.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| all six | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseRouting.kt` |
| the saga's four steps | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseInterceptors.kt` |
| the use cases | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseUseCases.kt` |
| the order screen | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseResultScreen.kt` |
| the history screen and its rows | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/HistoryScreen.kt` |

## Request and response bodies

`CreatePurchaseRequest` and `PurchaseOrderResponse` are in the contract file named above — do not copy
the fields. Two things about `PurchaseOrderResponse` are decisions rather than data:

- `status` is the **product's** word for the saga's phase, not petich's. petich ends a cleanly
  rolled-back saga in `FAILED`; the wire word is `compensated`, because nothing failed from the
  subscriber's side and the hold was reversed. The mapping is `OrderStatus.of` in
  `PurchaseDomain.kt`, and the vocabulary a client may branch on is
  `shared/components/src/commonMain/kotlin/io/konekt/components/Vocabulary.kt` (`OrderStatuses`).
- `priceText` and `reversalText` are **formatted on the server**. Only the server can format `Money`
  (D15), so the client cannot format it inconsistently.

The two component-tree routes answer through `respondKompotComponent`, never `call.respond` — a plain
respond drops the `"type"` discriminator on the **root** of the tree while every nested child
serialises perfectly, and the client then draws nothing for the whole screen.
`CallRespondUsageTest` refuses the other spelling in the sources.

## Errors

| Condition | Status | Body (`code` / `message`) |
|---|---|---|
| the plan id is not in the catalogue, at **start** time | `404` | `not_found` / `plan was not found` |
| the subscriber has no account row | `404` | `not_found` / `account was not found` |
| the order does not exist, **or belongs to somebody else** | `404` | `not_found` / `order was not found` |
| confirming an order that is already `COMPLETED`, `REJECTED` or rolled back | `409` | `conflict` / `this order has already finished` |
| confirming an order that is not waiting for a confirmation | `409` | `conflict` / `this order is not waiting for a confirmation` |
| no token, or a token whose family was revoked | `401` | Ktor's challenge, not an `ApiError` |

**A refusal by a saga rule is not an HTTP error.** A plan that is off sale, a price that moved, or a
balance that does not cover the purchase produce a `Reject` inside the validation step: the request
still answers `202`, and the order comes back with `status = "rejected"`. The reason is on the
screen, not in the status code. `KonektException.InsufficientFunds` exists and maps to `409`, and
**nothing in the product throws it today** — it is constructed only in `ErrorContractTest` — the balance check is a `Reject`.

## Quirks

- **A hold debits the visible balance.** The subscriber sees the money gone the moment they start a
  purchase and gets it back if they never confirm. The alternative — a held amount separate from an
  available one — is more honest and is a second number on every screen that shows a balance. This
  product has one number.
- **The confirmation has a five-minute deadline**, set on the step and not by the engine
  (`DEFAULT_CONFIRMATION_TTL`). It bounds how long a subscriber's own money sits held on a purchase
  they walked away from. When it passes, petich's sweeper compensates and the order becomes
  `compensated`.
- **The EXECUTION phase timeout is 30 seconds and not petich's default 10.** The canvas tells the
  subscriber a settlement "usually takes under 15 seconds", so the default would cancel a provider
  the screen describes. Raised in `Application.kt` from `MockPaymentGateway.EXECUTION_PHASE_TIMEOUT`.
- **The reversal is announced by the step being undone**, the hold — not by the announcing step.
  Compensation only walks back through steps that actually ran, and a purchase abandoned at the
  confirmation never reaches `POST_PROCESSING`; an announcement hanging off that step would never
  fire for the one case it exists for.
- **`/api/v1/screens/history/page` is written as a string in the server's own code**, in
  `HistoryScreen.pageUrl`, as well as being a `@Resource`. This repository's rule is that no endpoint
  path exists as a string outside a `*-shared-api` module (D13), and this is the one production source
  that breaks it: the same path is spelled twice with nothing holding the two spellings together.
  They agree today. The reason it happened is real — `LoadPageAction` takes a URL string, and the
  cursor inside it is opaque by design — but the fix belongs in the contract module, not here.
- **The cursor is a keyset, not an offset**, and it is opaque on the wire: a client that could
  construct one would be a client depending on the shape of a query.
