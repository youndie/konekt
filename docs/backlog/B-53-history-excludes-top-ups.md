---
id: B-53
title: "History reads entitlements, so the top-up the button beside it starts will never appear"
status: open
priority: P1
size: M
stage: stage-m3-product
epic: feature-buy-package
---

# B-53 — The two buttons on the balance disagree about what history is

`ExposedHistoryRepository.page` selects from `EntitlementTable`, left-joined to the ledger for the
reversal line. A top-up creates no entitlement — it raises a balance — so no top-up can ever be a row
in it. Section 05 of the canvas draws one explicitly: *"Top-up · 1 000 ₽ / 5b17-7702 · 26 Jun /
Completed"*, in the same list as the purchases.

This was invisible while [B-40](B-40-no-way-to-add-money.md)'s saga was unreachable. It stopped being invisible
the day the screen shipped: `Top up` and `History` now sit side by side on the balance card, and the
second does not show what the first does.

- **The decision and its reason.** The source has to move from the entitlement to the **ledger**,
  because the ledger is the record of what happened to the money and the entitlement is the record of
  what was bought. A union of two queries would keep the cursor honest only by accident — the cursor
  is `(createdAt, id)` and two tables interleave.
- The alternative — a second list, "payments", beside the orders — is worse for the reason the
  canvas gives by drawing one list: a subscriber reconciling a month wants one column of amounts,
  not two screens to add up.
- **Not covered:** the filter chips (All / Active / Refunded), which are
  [B-58](B-58-orders-filters.md), and the per-row remaining, which needs the counter.
- AC: a top-up appears in history with its amount signed the other way from a purchase, and the
  reference is the same one its result screen shows.
- AC: paging across a boundary where a top-up and a purchase share a second returns each row once —
  the case a two-query union gets wrong.
- Anchors:
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedHistoryRepository.kt`,
  `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/History.kt`.
