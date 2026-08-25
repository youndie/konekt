---
id: B-14
title: "The bridge from the petich outbox to booblik"
status: open
priority: P1
size: M
stage: stage-m2-live
blocked_by: [B-09, B-13]
---

# B-14 — The bridge from the petich outbox to booblik

petich provides the mechanism and delivers nothing: *"the transport (a queue, a webhook, a push) is
implemented by the application"* (research §1.7). `petich-outbox-core` gives at-least-once with
backoff and dead lettering; what is missing is the piece that reads a row and publishes it.

- **The decision and its reason.** A relay in the server process rather than a separate deliverer,
  because at-least-once is already handled by the outbox and a second process would add a failure mode
  without adding a guarantee. Publication is idempotent at the consumer, since at-least-once means the
  same event arrives twice under retry.
- The rejected alternative is publishing directly from the saga's post-processing step. It puts a
  network call inside the transaction boundary and reintroduces exactly the failure the outbox exists
  to remove.
- Not covered: the dead-letter path's operator surface. Rows land in the dead-letter table and are
  read with SQL for now.

- AC: a committed purchase saga results in one `orders` message, and a broker outage delays it rather
  than losing it.
- AC: replaying the same outbox row twice leaves the consumer's state unchanged.
- Anchors: `server/src/main/kotlin/io/konekt/events/OutboxRelay.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.7, D6.
