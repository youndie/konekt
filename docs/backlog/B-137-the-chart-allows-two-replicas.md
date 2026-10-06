---
id: B-137
title: "The chart refuses replicas > 1 for reasons B-134, B-135 and B-136 remove"
status: open
priority: P1
size: S
stage: stage-m8-two-replicas
blocked_by: [B-134, B-135, B-136]
---

# B-137 — two replicas become a supported configuration

`charts/konekt/templates/server.yaml` fails the render on `replicas > 1` (B-91) and names two reasons:
the in-memory bus and the consumer without a position. After B-134…B-136 both are gone, and the row
"More than one server replica" in `reference-scope.md` stops being a non-goal.

- **The decision:** `replicas > 1` renders only together with the realtime URL — the refusal moves
  from "never" to "not without a shared bus", and `scripts/chart-check.sh` proves that refusal names
  its reason, as it does today.
- `reference-scope.md` loses the row; `konekt-server.md` §5a ("what runs per replica") is rewritten
  as what runs per replica and what runs on the leader.
- The default stays one replica: the stand is sized for it.

- AC: `helm template` with `replicas: 2` and no realtime URL fails with the bus as the reason; with
  one, it renders.
- AC: no document in the tree still calls a second replica a non-goal (`stale_citations.py`).
- Anchors: `charts/konekt/templates/server.yaml`, `charts/konekt/values.yaml`,
  `scripts/chart-check.sh`, `docs/services/reference-scope.md`, `docs/services/konekt-server.md`.
