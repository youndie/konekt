---
id: B-136
title: "An SSE subscriber on one pod never sees an update produced on another"
status: open
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
- kesh in the chart as its own deployment, sized from kesh's measured chart. **Blocked outside this
  repository:** nothing publishes kesh's image — `deploy/` in youndie/kesh builds one, no workflow
  pushes it.
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
