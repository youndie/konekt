---
id: B-41
title: "The server emits order statuses the component dictionary does not declare, and declares one nothing emits"
status: open
priority: P2
size: S
stage: stage-m4-proof
epic: feature-purchase
---

# B-41 — The server emits order statuses the component dictionary does not declare, and declares one nothing emits

`OrderStatus` (server) and `OrderStatuses` (the wire dictionary in `:shared:components`) are two lists
of the same thing and they disagree in both directions.

The server can emit six words — `pending`, `awaiting_confirmation`, `completed`, `rejected`,
`compensated`, `compensating` — because `PurchaseOrderResponse.status` and `TopUpResponse.status`
carry `OrderStatus.wireName` straight through. The dictionary declares five, and they are not the
same five: it has `failed`, which **no producer emits** (petich's `FAILED` maps to `COMPENSATED`, and
that mapping is deliberate and documented), and it lacks `rejected` and `compensating`.

Found writing B-40's scenarios: a top-up refused by the limits answers `rejected`, and the assertion
could not be written against a constant because there is none.

- **Two separate defects with one cause.** `failed` is dead vocabulary — the `written-but-never-called`
  shape, one list away from the code that would have shown it. `rejected` and `compensating` are the
  reverse: real values travelling on the wire that the dictionary never named, so a client meeting one
  falls to the neutral form. That degradation is by design for an open string and it is still wrong
  here, because these two are not exotic — a rejected order is the ordinary answer to a rule refusing.
- **The sharper half is in the history row.** `HistoryScreen` maps `OrderStatus` to the dictionary
  with `else -> OrderStatuses.PENDING`, so a **rejected** order and a **stuck** one are both drawn as
  *pending* — a row that says "in progress" for an order that has finished and for one that needs a
  person. That is a product misstatement rather than a naming issue.
- The rejected alternative is generating the dictionary from `OrderStatus`. It would close the gap and
  put a server enum on the wire, which is exactly what the open-string decision refuses
  (research-architecture §1.4): an enum closes the set at the client's build date.
- Not covered: whether `compensating` should reach a subscriber at all. It is the state that needs a
  person, and "we are looking at it" may be the honest word rather than the internal one.

- AC: every value `OrderStatus.wireName` can produce is declared in `OrderStatuses`, and a test walks
  the enum rather than a hand-written list — a second hand-written list is what this item is about.
- AC: a value in `OrderStatuses` that no `OrderStatus` produces fails that same test.
- AC: a rejected order and a compensating one are distinguishable in the history row.
- Anchors: `feature/purchase-server-domain/.../PurchaseDomain.kt`,
  `shared/components/src/commonMain/kotlin/io/konekt/components/Vocabulary.kt`,
  `feature/purchase-server-data/.../HistoryScreen.kt`.

Background: found by [B-40](B-40-no-way-to-add-money.md); the open-string decision is
[research-architecture](../research/research-architecture.md) §1.4.
