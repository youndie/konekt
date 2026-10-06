---
id: B-134
title: "The usage consumer loses every event that arrives while it is down, and applies each one once per replica"
status: done
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

## Findings — 2026-10-06

- **Done.** `consumer_position` (V16) and `ConsumerPositions` in `:shared:db`; `UsageConsumer.drain`
  moves the position first and applies the batch inside `advance`, pushes after the commit;
  `UsageChain.start` seeds the end of the log on a first start, refuses a position above the high
  watermark, and stores a jump to `logStartOffset` with the loss logged. The runtime
  `OFFSET_OUT_OF_RANGE` recovery now resumes at the log's START and stores the jump; it used to go to
  the end, throwing away everything retention had kept.
- **Joining is proved, not assumed:** a repository's own `suspendTransaction` inside `advance` joins it —
  `ConsumerPositionsTest` writes through one, fails before the commit, and finds neither the write nor
  the move afterwards (values read outside the transaction).
- **Every AC has a test, and each test was shown to fail:** effects moved outside the transaction →
  `an effect written through a repository rolls back with the position` red; the position never moving
  → the restart test red (9 300 instead of 9 400: the first batch applied twice); the compare-and-set
  removed → both "once" tests red (9 800 with two consumers); the refusal removed → the refusal test red.
- **A swallowed failure inside a batch rolls the batch back, not one event.** `ConsumeUsageUseCase`
  wraps the repository in `suspendRunCatching`, so a database error is read as "no counter" — but the
  Postgres transaction is then aborted, the commit fails, and the whole batch is read again. Correct for
  a transient failure; a permanent one would stop the consumer on that batch instead of skipping one
  event. Not reproduced, and no such failure is known — written down rather than designed for.
- **Not exercised:** a stored position below `logStartOffset` at start (needs retention to delete a
  segment under a stopped consumer). The branch logs and stores the jump; it has no test.
- The chart's refusal of `replicas > 1` still gives the double decrement as a reason; that sentence is
  rewritten with the refusal itself in [B-137](B-137-the-chart-allows-two-replicas.md).
