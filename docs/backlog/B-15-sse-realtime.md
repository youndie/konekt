---
id: B-15
title: "The realtime transport: an SSE endpoint and a client source"
status: open
priority: P1
size: M
stage: stage-m2-live
blocked_by: [B-07]
---

# B-15 — The realtime transport: an SSE endpoint and a client source

kompot ships the contract and refuses to choose a transport: `kompot-realtime` is
`KompotRealtimeSource`, `UpdateComponentMessage` and `KompotScreenResponse`, and the readme says the
SSE or WebSocket implementation is the application's (research §1.6). `kompot-realtime-server` gives
the broadcaster and an in-memory bus, which is the whole requirement for one process.

- **The decision and its reason.** Server-Sent Events. The traffic is one-directional, SSE survives
  proxies that mishandle upgrades, and reconnection with `Last-Event-ID` is in the protocol rather
  than in our code. No Redis, because there is one instance and `kompot-realtime-redis` exists for the
  multi-instance case.
- The rejected alternative, WebSocket, buys a client→server channel the product does not need: every
  subscriber action is already an HTTP action.
- Not covered: delivery guarantees. A component update is losable by design — the client gets current
  state with its next screen request.

- AC: a counter changed on the server updates the open home screen without a fetch.
- AC: a dropped connection resumes and the screen converges, verified by killing the stream mid-test.
- Anchors: `server/src/main/kotlin/io/konekt/realtime/`,
  `client/src/commonMain/kotlin/io/konekt/realtime/SseRealtimeSource.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.6, D7.
