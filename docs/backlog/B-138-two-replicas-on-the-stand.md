---
id: B-138
title: "Two replicas are proved on the stand, through a kill, not only in tests"
status: done
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

## Findings — 2026-10-06

- **Done, on kind rather than on the cluster the product runs on.** Killing pods on purpose belongs on
  a stand nobody else uses; kind runs the same chart with the same images (the server built from this
  tree, the chart's own pins for Postgres, booblik and kesh). `scripts/rolling-check.sh two-replicas`
  creates or reuses the cluster, installs a fresh release (volumes included) with two replicas and
  kesh, and runs `:e2e:twoReplicasCheck`.
- **The verdict:** 45 usage events published by the check — 10 with both pods up, 10 across a forced
  kill of the leader, 20 across a rolling restart, 5 to clients on the new pods — and 45 MB applied,
  unchanged five seconds later; at most one leader in 230 samples; in phase 1 each pod's client heard
  all 10 updates, half of them applied on the other pod. **Control:** the same release with the bus in
  memory (one replica scaled to two past the chart's refusal) fails with "the client on … heard nothing
  while both pods were up".
- **The chart rolled nothing before this item:** `strategy: Recreate` would have taken both replicas
  down on every upgrade. More than one replica is now `RollingUpdate` (`maxSurge: 1`,
  `maxUnavailable: 0`), one is still recreated; `chart-check.sh` reads it off the render. Chart 0.9.0.
- **Deviations from the item, and why.** The simulator is off: its usage would spend the same counter
  and turn the exact sum into a range; its singleton is `SingletonsTest`'s and the compose stand's.
  The rolling update is a restart of one release rather than two releases side by side: what this item
  asks of it — the election handed over by a drain, positions and the bus across pod replacement — is
  the same, and old code against a new schema is `rolling-check.sh`'s own case. Outbox rows against
  broker records are not counted here: the relay is at-least-once by design, so a kill legitimately
  repeats a row, and exactly-once with two replicas is `SingletonsTest`'s (20 rows, 20 records).
- **Found on the way:** a pod being deleted is still `Running` and, until the new pods win the election,
  still the leader — the first run's pod list named it and waited on a stream it was closing. The check
  excludes pods with a deletion timestamp.
- kind's `load` fails on multi-platform images (the quirk kesh's stand met); the node pulls those.
