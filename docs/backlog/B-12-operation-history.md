---
id: B-12
title: "Operation history, including the entries that did not happen"
status: done
priority: P2
size: M
stage: stage-m1-money
epic: feature-account-history
blocked_by: [B-08]
---

# B-12 — Operation history, including the entries that did not happen

Charges, top-ups, purchases and activations in one list. The interesting rows are the compensated
ones: an order that was reversed is a row, not an absence, and the canvas draws it with the refund
line beneath it.

- **The decision and its reason.** The list is a `paginated_list` from `kompot-standard` with
  `order_row` items, so pagination and load-more come from the toolkit and only the row is ours.
  Research §1.5 — the toolkit's list already terminates correctly and the TCK checks that it does.
- The rejected alternative is our own list component. It would need its own pagination contract and
  would fall outside the conformance walk.
- Not covered: filtering and search. The list is chronological.

- AC OK: a compensated order carries `−$12` on the row and `$12 returned to balance on 29 Jun —
  nothing was activated.` beneath it, with the reference. **The debit stays on the row.** Netting it
  against the reversal to zero would make the reversal invisible, which is the one thing this screen
  must not do.
- AC OK: a walk over every page visits each order exactly once and stops. The seeded case is seven
  rows sharing a single instant — the case a timestamp-only cursor gets wrong in both directions:
  `< instant` drops all seven at a boundary and `<= instant` returns them forever. Two purchases in
  one millisecond are not exotic; a retry does it.
- AC OK: an exactly-full page still knows whether there is more. Three rows at three per page ends,
  and a fourth row makes it not end — the off-by-one every keyset gets wrong once, and the reason
  "there is more" comes from fetching **one row beyond the limit** rather than from a `COUNT`, which
  is a second query against a table still being written to.
- Anchors: `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/History.kt`,
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedHistoryRepository.kt`,
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/HistoryScreen.kt`,
  `feature/purchase-server-data/src/test/kotlin/io/konekt/feature/purchase/server/data/HistoryPagingTest.kt`.

## What is in the list, and what is not

Everything that **moved money**. A purchase refused in validation never held anything, so it is not
here: there is nothing to reconcile against a bank statement, and a list of refusals answers a
different question on a different screen.

A compensated order **is** here, with its reversal line. A history that quietly omitted what was
undone would be a history a subscriber cannot reconcile, which is the one job it has.

## Two things the toolkit does and one it does not

`paginated_list` carries pagination, load-more and termination, so the conformance walk checks them
rather than us. What stays ours is the row — the only part that is about this product. The empty state
is drawn rather than left blank, because an empty list and a list that failed to load look identical
as nothing.

The cursor is opaque on the wire and refused rather than half-read when it is not one. A half-read
cursor is a page boundary nobody chose.

The join to the reversal carries its condition in the JOIN and not in the WHERE. In the WHERE it
would silently become an inner join and every order without a reversal would vanish from the history —
which is most of them.

Background: [design-app-canvas](../design/design-app-canvas.md) section 05.
