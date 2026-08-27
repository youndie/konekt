---
id: B-53
title: "History reads entitlements, so the top-up the button beside it starts will never appear"
status: done
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

## What landed

**The list is driven by the ledger, not by the entitlement.** One query, one driving row per
movement: a purchase writes exactly one `hold` and a top-up exactly one `top_up`. `capture` and
`release` are consequences of a movement rather than movements, so they are joined to; `decline` is
zero-sum and moved nothing to reconcile. The keyset stays over one table, which is what makes it
honest — the union of two queries pages each source separately and a boundary falling between them
either repeats a row or drops one.

**The purchase rows are provably the same set as before.** `HoldFundsInterceptor` writes the hold and
the pending entitlement together, so a hold without an entitlement cannot exist. That is why the
driver could move without the screen changing for anything that was already on it.

**Three things had to stop being true of the screen**, and each was right until credits joined:
- the amount was negated on every row — a credit drawn as `−$25` is the opposite of what happened, on
  the one screen a subscriber reconciles against a bank statement. The ledger's sign travels with the
  entry now;
- the reversal sentence said "returned to balance", which for a taken-back top-up is backwards —
  the money left the balance, and somebody reading the purchase's sentence goes looking for an amount
  that is not there;
- `Paid` is a word about money leaving. A credit says `Added`, and a reversed one `Taken back`.

**The fixture had to become honest before the tests could run.** `HistoryPagingTest.order()` wrote an
entitlement and no ledger row — a purchase that reserved nothing, which the product cannot produce.
It went unnoticed while the query read entitlements. A fixture that cannot be built the way the
product builds it was never testing the product.

Verified on the stand: two top-ups and three purchases, newest first, in one list with the right
signs and words.

**Not done here:** the filter chips, which are [B-58](B-58-orders-filters.md).
