---
id: B-08
title: "The purchase saga: four interceptors, with the confirmation as a suspend"
status: open
priority: P0
size: L
stage: stage-m1-money
epic: feature-buy-package
blocked_by: [B-02]
---

# B-08 — The purchase saga: four interceptors, with the confirmation as a suspend

Buying a package is the operation the whole build exists to show end to end. petich's phases are
fixed — `ENRICHMENT → VALIDATION → AUTHORIZATION → EXECUTION → POST_PROCESSING` — and a step that
needs a human returns `InterceptorResult.Suspend(requiredAction, ttl)`, after which a sweeper rolls
the saga back exactly as a refusal would if nobody comes back.

- **The decision and its reason.** Four interceptors, not six: validation, authorization (hold, then
  suspend for confirmation), execution (charge and provision), post-processing (emit). petich's own
  measurement is ≈9 database writes for four steps against ≈17 for six, taken through
  `pg_stat_user_tables`, and the saga table is written at every step boundary by design.
- The rejected alternative splits hold and confirm into separate interceptors for readability. It
  costs about eight extra writes on the most frequent operation in the product to make one
  interceptor easier to read.
- Not covered: what the TTL should be. That is open question 2 in the research and is answered here,
  in writing, with the number that was chosen.

- AC: a confirmed purchase leaves the account debited, the package active and the order `COMPLETED`.
- AC: a purchase left unconfirmed past its TTL is rolled back by the sweeper with the balance restored,
  and the order reads `compensated` rather than `failed`.
- Anchors: `server/src/main/kotlin/io/konekt/orders/purchase/`.

Background: [research-architecture](../research/research-architecture.md) §1.7, D5, open question 2.
