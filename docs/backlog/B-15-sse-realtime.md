---
id: B-15
title: "The realtime transport: an SSE endpoint and a client source"
status: wip
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
- **The engine is CIO** (`io.ktor.server.cio.CIO`), and this endpoint is why: many long-lived, mostly
  idle streams is the profile a coroutine-per-connection engine is shaped for and a thread pool is
  not. Any file here that also builds an `HttpClient` needs an import alias — the client's `CIO` has
  the same simple name, and without one `embeddedServer` silently takes the client engine.
- Not covered: delivery guarantees. A component update is losable by design — the client gets current
  state with its next screen request.

- AC OK (server half): a counter changed on the server arrives on that subscriber's open SSE stream
  as an `UpdateComponentMessage` a client can decode, asserted through a real connection. The
  component id is **derived from the counter** rather than generated, because an update names the
  node it replaces — a random id is a frame that arrives and changes nothing, silently.
- AC OK: a stream carries only its own subscriber's updates. The topic comes from the verified token
  and never from a request parameter; a stream addressed by a parameter is every subscriber's screen
  for anybody who asks.
- AC PENDING, **client half**: reconnection with `Last-Event-ID` and a `KompotRealtimeSource` need a
  client module. `B-04`/`B-07`.
- Also: a client that goes away is forgotten. The unsubscribe is in a `finally` because the ordinary
  end of a stream is a closed laptop rather than a graceful close, and a subscriber set that only
  shrinks on a clean exit is a set that only grows.
- Anchors: `server/src/main/kotlin/io/konekt/realtime/RealtimeRouting.kt`,
  `server/src/test/kotlin/io/konekt/realtime/RealtimeStreamTest.kt`.

Two notes worth keeping. kompot's broadcaster **refuses to broadcast if it was never started** and
says so — the failure it prevents is a publish reaching a bus nobody collects from, which is silence
and therefore the hardest kind to attribute. And there is no `ktor-client-sse` artefact: the client
plugin is in `ktor-client-core`, which is easy to assume otherwise from the server side, where
`ktor-server-sse` *is* one.

Background: [research-architecture](../research/research-architecture.md) §1.6, D7;
[research-stack](../research/research-stack.md) D19.
