---
id: B-58
title: "Orders has no filters and an active order shows nothing about what is left of it"
status: open
priority: P2
size: M
stage: stage-m3-product
epic: feature-buy-package
---

# B-58 — Section 05 asks a list to answer two questions, and it answers one

The served history is a `paginated_list` of `order_row`, each carrying title, reference, date, amount
and status. Section 05 draws the same list with two things this one does not have:

- **Filter chips: All / Active / Refunded.** They are a query parameter and a row of controls, and
  the endpoint already pages — so this is a `status` filter on the cursor query rather than anything
  structural.
- **Per-row state for an active order: "15,8 GB left · 18 days", and a `Top up` action.** This is the
  expensive half: the row is built from the entitlement and the remaining quota lives in the usage
  feature's counters. A history repository reaching into usage's tables is how two features become
  one — so the composition happens where it happens for the home screen, in `:server`, or the row
  carries a field the routing fills.

- **The decision and its reason.** Take the chips first and separately. They need no cross-feature
  read, they change what the screen is FOR — a subscriber looking for a refund is not scrolling — and
  they are the half that will still be right after `Top up` on a row moves somewhere else.
- **The `Top up` on a row is not this product's top-up.** The canvas means topping up THAT PACKAGE's
  data, which is a different verb from raising a balance — there is no such saga. It is a separate
  item the day somebody wants it, not a button wired to the balance form.
- **Not covered:** whether a top-up appears in this list at all — [B-53](B-53-history-excludes-top-ups.md).
- AC: choosing Refunded shows the compensated orders and nothing else, and the count of rows across
  the three chips is not more than All.
- Anchors:
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/HistoryScreen.kt`,
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedHistoryRepository.kt`.
