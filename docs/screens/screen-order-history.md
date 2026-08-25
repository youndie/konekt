---
id: screen-order-history
title: History — everything that moved money
type: client_screen
platform: [jvm]
status: active
entry:
  jvm: "GET /api/v1/screens/history — a server-built tree; the next page is GET /api/v1/screens/history/page"
parent_feature: feature-plan-purchase
calls_api:
  - endpoint-purchase
source: feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/HistoryScreen.kt
---

# Screen: history

> A kompot `paginated_list` of konekt `order_row`s. Pagination, load-more and termination come from
> the toolkit; what stays ours is the row — and the row is the only part that is about this product.
>
> Read out of the source on 2026-08-25.

## 0a. Code anchors

| What | File |
|---|---|
| The tree and the page response | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/HistoryScreen.kt` |
| The routes | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/PurchaseRouting.kt` |
| The cursor and the page | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/History.kt` |
| The query | `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedHistoryRepository.kt` |
| The row's wire type | `shared/components/src/commonMain/kotlin/io/konekt/components/OrderRowComponent.kt` |
| Tests | `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/HistoryPagingTest.kt` |

## 0. Entry point and visibility

- **Entry point:** `GET /api/v1/screens/history`. Nothing in this build links to it.
- **Shown when:** the caller holds a valid access token. The list is scoped to the token's subscriber
  and never to a parameter.

## 1. Screen states

- [x] **Content:** a `paginated_list` with id `history`, its `initialItems` a page of `order_row`s and
  its `loadMoreAction` a `LoadPageAction` — or **absent**, which is what stops the client asking.
- [x] **Empty:** the list's own `emptyState`, a `text` with id `history-empty`: "Nothing here yet.
  Your purchases and top-ups will appear on this screen." Drawn rather than left blank — an empty
  list and a list that failed to load look identical as nothing, and only one of them is worth
  waiting for. *("top-ups" is copy about a feature that does not exist: `B-40`.)*
- [ ] **Loading:** the toolkit's, not ours. `skeleton` is in the dictionary and nothing here emits one.
- [ ] **Error:** not built.

## 2. API integration

| Call | Contract | Endpoint document |
| :--- | :--- | :--- |
| `GET /api/v1/screens/history` | `HistoryScreenResource` | [endpoint-purchase](../api/endpoint-purchase.md) |
| `GET /api/v1/screens/history/page?cursor=…` | `HistoryScreenResource.Page` | [endpoint-purchase](../api/endpoint-purchase.md) |

The second answers a `KompotPageResponse` — items to **append**, not a screen to replace.

## 3. Initialisation

**Input parameters:** none. The first request carries no cursor.

| Call | Case | Handling | Screen state |
| :--- | :--- | :--- | :--- |
| `GET .../history` | `200`, rows | render the list | **Content** |
| `GET .../history` | `200`, no rows | the list draws its `emptyState` | **Empty** |
| `GET .../history/page` | `200`, `nextLoadAction` present | append and keep the button | **Content** |
| `GET .../history/page` | `200`, `nextLoadAction` null | append and stop | **Content, terminated** |

## 4. UI elements

### 4.1. Order row

- **id:** `history-<orderId>` — note it differs from the id the result screen uses for the same
  order (`order-<orderId>`), because the two screens draw different rows about it.
- **`reference`:** `orderId.take(8)`.
- **`amountText`:** `MoneyFormat.format(-amount, signed = true)` — **the debit, always, even on a
  compensated order.** The row says what left; the note says it came back. Netting the two to zero
  would make a reversal invisible, which is the one thing this screen must not do.
- **`dateText`:** `DayFormat.dayAndMonth(entry.at)`.
- **`status` / `statusText`:** `completed`/"Paid", `compensated`/"Reversed", everything else
  `pending`/"Awaiting confirmation".
- **`noteText`:** present only when the money came back — "*$12 returned to balance on 28 Jun —
  nothing was activated.*"
- **On tap:** nothing. The row carries no action, so there is no route from this screen to
  [screen-purchase-result](screen-purchase-result.md) even though both exist.

## 5. Navigation (summary)

- "Load more" ──▶ the same screen, extended. Nothing else.

## 6. Quirks

- **What is in the list is a decision, not a query: everything that MOVED MONEY.** A purchase refused
  in validation never held anything, so it is not here — there is nothing to reconcile against a bank
  statement, and a list of refusals is a different screen answering a different question. A
  compensated order **is** here, with its reversal line. Mechanically this falls out of the query: the
  list is built from entitlements, and an entitlement is created by the hold step.
- **The cursor is a keyset pair `(instant, orderId)`, not an offset.** An offset skips or repeats when
  a row lands above it between two pages, and history grows at exactly the end a subscriber is reading
  from. The tie-break is in the `ORDER BY` as well as in the cursor: a tie-break that is not total is
  a page boundary that loops.
- **"Is there another page" is answered by fetching one row more than asked for**, never by a
  `COUNT` — a second query against a table still being written to can disagree with the first in the
  moment that matters.
- **The reversal is a `LEFT JOIN` with the kind in the join constraint, not in the `WHERE`.** In the
  `WHERE` it silently becomes an inner join and every order without a reversal vanishes from the
  history.
- **The page size is always 20 and no request can change it.** `LoadHistoryUseCase.MAX_PAGE_SIZE`
  (100) is **declared and referenced by nothing** — there is no route parameter for a page size to
  bound.
- **`HistoryScreen.pageUrl` writes the path as a string**, in the server's own code, beside the
  `@Resource` that already declares it. See the quirk in
  [endpoint-purchase](../api/endpoint-purchase.md).
