---
id: B-138
title: "Two replicas are proved on the stand, through a kill, not only in tests"
status: wip
priority: P2
size: M
stage: stage-m8-two-replicas
blocked_by: [B-137]
---

# B-138 — two replicas on the stand, and the leader killed under traffic

Each of B-134…B-136 is proved by two instances in one test JVM. What none of them sees is the chart:
the pool size with a leadership in it, kore's drain handing the election over, kesh's limits under
real SSE fan-out, and a rolling update with two pods of different releases.

- Deploy with `replicas: 2` and the simulator on; kill the leader pod; a rolling update.
- Verdicts by numbers the stand already exports, not by eye: decrements applied against usage
  published (equal, before and after the kill), outbox rows against booblik records, one leader at
  every sample, an SSE client on each pod seeing every update.
- `scripts/rolling-check.sh` grows the two-replica case rather than gaining a sibling.

- AC: across a leader kill and a rolling update, no usage event is applied twice or lost, and every
  SSE client sees every update produced after it connected.
- Anchors: `scripts/rolling-check.sh`, `scripts/deploy-check.sh`, `charts/konekt/values.yaml`.
