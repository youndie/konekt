---
id: B-12
title: "Operation history, including the entries that did not happen"
status: open
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

- AC: a compensated order appears with its reversal line and its reference.
- AC: paging to the end terminates and the TCK's pagination check passes against this route.
- Anchors: `server/src/main/kotlin/io/konekt/screens/HistoryScreen.kt`.

Background: [design-app-canvas](../design/design-app-canvas.md) section 05.
