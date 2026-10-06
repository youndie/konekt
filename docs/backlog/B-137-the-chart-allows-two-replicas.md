---
id: B-137
title: "The chart refuses replicas > 1 for reasons B-134, B-135 and B-136 remove"
status: done
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

## Findings — 2026-10-06

- **Done.** `server.yaml` refuses `server.replicas > 1` only with `kesh.enabled` off, naming the bus;
  the simulator refusal is gone — since B-135 the simulator runs on a leader, in an election of its own.
  `scripts/chart-check.sh` refuses two replicas without the bus and renders two with it, simulator on
  and off; with the refusal made blind to `kesh.enabled` (the control), "two replicas with the shared
  bus" fails to render. Chart 0.7.0 → 0.8.0.
- `reference-scope.md` loses the row and gains it under "what is not on this list", with the three
  items that removed its reasons; `konekt-server.md` §5a is "one replica by default, more with the
  shared bus"; `values.yaml` says why each old reason is gone.
- **Left for B-138:** the server Deployment keeps `strategy: Recreate`, so an upgrade of two replicas
  still takes both down at once. A rolling update is what B-138 exercises on the stand, and choosing it
  here, unexercised, would be a promise this item cannot check.
