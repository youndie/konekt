---
id: B-134
title: "The usage consumer loses every event that arrives while it is down, and applies each one once per replica"
status: open
priority: P1
size: M
stage: stage-m8-two-replicas
---

# B-134 — the usage position is stored in the transaction that applies the usage

`UsageConsumer` starts at the high watermark (`mocks/traffic/UsageChain.kt`), keeps its position in
memory and applies every record in its own `dbQuery` with a bare `remaining - units`
(`ExposedUsageCounters`). So a restart loses everything published while the pod was down, a crash
mid-batch keeps half a batch, and two replicas apply every event twice — the second reason
`charts/konekt/templates/server.yaml` refuses `replicas > 1`. This is a defect of one replica before it
is a blocker for two.

- **The decision: booblik's recipe**, `feature-consumer-position` in youndie/booblik — the position in a
  `consumer_position` row, updated **first** in the same transaction as the decrement, guarded by
  `next_offset = :baseOffset`; zero rows updated means another reader applied the batch, and the
  transaction rolls back. The broker keeps no positions and will not (booblik R12); the recipe needs
  no change to it.
- One transaction per batch, not per record: the repository takes the position with the batch, or an
  outer `suspendTransaction` is joined by the inner ones — and joining is proved by a test that reads
  inside and asserts outside, because `transaction {}` and `suspendTransaction {}` do not see each other.
- On start the stored position is checked against METADATA: below `logStartOffset` is a loss with a
  known size, logged and counted, then the consumer continues from `logStartOffset`; above
  `highWatermark` the consumer refuses to start. The first run records an explicit `Latest`.
- The SSE push stays after the commit: at-most-once, and the next fetch carries the right state —
  kompot's own reasoning, already in `reference-scope.md`.
- Not covered: a recreated log that has grown past the stored position — invisible until booblik
  M-171 (log identity in METADATA).

- AC: a consumer stopped at offset N, with M events published while it is down, applies exactly M
  decrements after it starts again; today it applies none.
- AC: two consumers on one partition apply each event once — the counter ends where one consumer
  would have left it — and the loser's transactions roll back.
- AC: a crash between the position update and the commit leaves both untouched; the batch applies
  once after restart.
- AC: a stored position above `highWatermark` stops the start with both numbers in the message.
- Anchors: `server/src/main/kotlin/io/konekt/mocks/traffic/UsageConsumer.kt`,
  `server/src/main/kotlin/io/konekt/mocks/traffic/UsageChain.kt`,
  `feature/usage-server-data/src/main/kotlin/`, `shared/db/src/main/resources/db/migration/`.
