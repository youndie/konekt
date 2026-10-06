---
id: B-136
title: "An SSE subscriber on one pod never sees an update produced on another"
status: done
priority: P1
size: M
stage: stage-m8-two-replicas
---

# B-136 — the realtime bus goes through kesh

`serverModule` binds `KompotUpdateBroadcaster()`, kompot's in-memory bus (B-91): an update produced
on one pod reaches only the SSE connections held by that pod. kompot already ships the shared bus,
`kompot-realtime-redis` (PUBLISH plus one PSUBSCRIBE, Lettuce), and kesh — the portfolio's
RESP2 store — runs that exact bus in its conformance suite.

- **The decision: `kompot-realtime-redis` against kesh**, switched on by a URL in the environment;
  without one the in-memory bus stays, and is right for one replica. No code in kompot.
- kesh in the chart as its own deployment, sized from kesh's measured chart. The image is
  `ghcr.io/youndie/kesh`, published on every push to kesh's `main` since youndie/kesh B-32; the chart
  pins a `sha-<commit>` tag, never `main`.
- The test starts the bus before broadcasting: kompot's own two-instance test does not, and fails on
  Redis too (found by kesh's conformance run).
- Not covered: kesh replication or persistence — an update is losable by kompot's design, and the next
  fetch carries current state.

- AC: two server instances, one kesh: an update produced through one reaches an SSE subscriber of the
  other.
- AC: with kesh unreachable the server starts, says so, and keeps serving fetches; live updates
  resume when kesh returns.
- Anchors: `server/src/main/kotlin/io/konekt/Application.kt` (`serverModule`),
  `charts/konekt/templates/`, `gradle/libs.versions.toml`.

## Findings — 2026-10-06

- **Done.** `KONEKT_REALTIME_URL` (empty = the in-memory bus, as before) switches the broadcaster to
  `kompot-realtime-redis` over kesh, through `io.konekt.realtime.SharedUpdateBus`. The stand runs kesh
  (`ghcr.io/youndie/kesh:sha-4ad4979…`) and points both servers at it; the chart gains `kesh.yaml`
  behind `kesh.enabled` (off by default), with a NetworkPolicy and kesh's own memory arithmetic, and
  sets the server's URL when it is on. Chart 0.6.0 → 0.7.0.
- **kompot's bare bus takes a server down two ways, and both were measured, not supposed.** A
  subscription that fails once — kesh late at startup — is never collected again: with the retry in
  `SharedUpdateBus` removed, `with kesh unreachable … live updates resume when kesh returns` is red.
  A publish while the connection is gone is queued by Lettuce and waits for the reconnect: with the
  timeout removed, `a publish while kesh is gone returns instead of waiting for it` did not return in
  15 s. The usage consumer pushes right after its commit, so that wait would have stopped it.
- **AC, walked:** an update broadcast through one replica reaches a subscriber of another, and only
  that subject's (`SharedUpdateBusTest`, against kesh from its published image); with kesh unreachable
  the replica starts, a publish returns at once, and updates resume when kesh appears on the address.
  The two-server stand is the next check — B-135 is rebased on this branch and its e2e is the one that
  failed for the lack of this bus.
- Found on the way: `charts/konekt/values.yaml` still said retention costs nothing because the
  consumer starts at the end of the log — false since B-134; corrected in the chart change.
- Reported upstream? Not needed — both behaviours are kompot's documented stance (an update is
  losable); the resilience is konekt's concern, and kompot's README already says the transport is the
  application's. A line could be offered to kompot as an option of the Redis bus later.
