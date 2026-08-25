---
id: B-14
title: "The bridge from the petich outbox to booblik"
status: done
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

- AC OK: a row in the outbox reaches the `orders` topic on a real broker, and the row stops being
  pending only once it did.
- AC OK: a broker that refuses **delays** the row. The important half is not that the publish failed
  — it is that the row is still there afterwards, which is the whole difference between an outbox and
  a network call inside a saga. Asserted with a test time source, so the backoff is moved rather than
  waited out, and the tick inside the backoff is asserted **not** to retry: a poller that hammers a
  broker which is down is how an outage becomes a longer outage.
- AC OK, and the first attempt at it was wrong. A redelivery is the same row delivered twice, which
  happens when the publish lands and the "delivered" mark does not — what a crash between the two
  leaves behind. The first version inserted a second row with a doctored id, which made the assertion
  trivially false: a duplicate with a *different* id is not a redelivery, it is a different event, and
  testing it proves the opposite of the point. It is now simulated by a repository that loses the
  first mark.
- Anchors: `server/src/main/kotlin/io/konekt/events/BooblikOutboxPublisher.kt`,
  `server/src/main/kotlin/io/konekt/events/BrokerConnection.kt`,
  `server/src/test/kotlin/io/konekt/events/OutboxRelayTest.kt`.

## Two decisions inside the publisher

**Keyed by the order, not by the event id.** Partitioning by key is what keeps every event about one
order in one partition, and a partition is the only place booblik promises an order at all — so
"reversed" cannot overtake "completed" for the same purchase. Keying by the event id would spread one
order's story across partitions and lose exactly that.

**The send is awaited.** A broker that refused the record then raises in the relay, which leaves the
row pending and tries again. Fire-and-forget would mark it delivered on a write nobody confirmed,
which is the one way an outbox still loses an event after all the machinery around it.

The producer is held for the process rather than made per call: it is an accumulator with a coroutine
of its own, and booblik's own measurement puts batching at 54× against sending one record at a time.
One per event would give that up and leak a coroutine on every publish.

Background: [research-architecture](../research/research-architecture.md) §1.7, D6.
