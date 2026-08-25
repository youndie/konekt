---
id: endpoint-home
title: The home screen and the live update stream
type: api_endpoints
status: active
services:
  - konekt-server
contract_source:
  - konekt:feature/usage-shared-api HomeScreenResource
  - konekt:feature/realtime-shared-api RealtimeStream (a constant, not a @Resource — see below)
parent_feature: feature-usage-allowance
---

# API: the home screen and the live update stream

> Two routes that answer the same screen from two directions: one builds it, the other keeps one node
> of it current. See [screen-home](../screens/screen-home.md).
>
> Read out of the source on 2026-08-25.

## Routes — all of them, no exceptions

| Method and path | Auth tier | Answers | Purpose |
|---|---|---|---|
| `GET /api/v1/screens/home` | **user token** | `200` + a component tree | balance and every counter the subscriber holds |
| `GET /api/v1/realtime` | **user token** | `200` + `text/event-stream` | one subscriber's `UpdateComponentMessage` frames |

Both are mounted by `homeRoutes()` and `realtimeRoutes()`, in the `AuthTier.USER` group of
`konektRoutes` in `server/src/main/kotlin/io/konekt/Application.kt`.

**The stream's topic comes from the verified token and never from a parameter.**
`RealtimeStream.topicOf(call.subscriberId())` — a stream addressed by a query parameter is every
subscriber's screen for anybody who asks. The client's `subscribe(topic)` argument is ignored for the
same reason.

**`/api/v1/realtime` is the one path in the product written as a constant rather than a
`@Resource`**, and that is not an exception being tolerated quietly. Both halves of SSE take a plain
string — Ktor's server builder is `sse(path)` and the client's is `serverSentEvents(urlString = …)` —
and `ktor-client-resources` has no SSE builder to type either. So the rule is kept the only way it
can be: the string exists once, in `feature/realtime-shared-api`, and both sides name that constant.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `GET /api/v1/screens/home` | `server/src/main/kotlin/io/konekt/screens/HomeRouting.kt` |
| the tree it builds | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` |
| the counter cards inside it | `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/UsageCounterCards.kt` |
| `GET /api/v1/realtime` | `server/src/main/kotlin/io/konekt/realtime/RealtimeRouting.kt` |
| what pushes a frame | `server/src/main/kotlin/io/konekt/realtime/RealtimeRouting.kt` — `ComponentBroadcaster` |
| the only producer of frames today | `server/src/main/kotlin/io/konekt/mocks/traffic/UsageConsumer.kt` |
| the client end | `client/src/commonMain/kotlin/io/konekt/client/realtime/SseRealtimeSource.kt` |

**The home screen is assembled in `:server` and not in a feature.** The balance belongs to the
purchase feature's ledger and the counters to the usage feature, and a feature reaching into the
other's repository to draw one screen is how two features become one.

## Request and response bodies

No request bodies. The home route answers a `column` of kompot `text` nodes and konekt
`usage_counter_card`s, written with `respondKompotComponent`.

Each SSE event's `data` is one `UpdateComponentMessage` — kompot's own type, `(componentId,
component)` — encoded with the application's `Json`. **The component id names the node the client
already has**: `counter-<kind>`, derived from the counter by `UsageCounterCards.idOf`. A generated id
would be a frame that arrives and changes nothing, silently.

## Errors

| Condition | Status | Body |
|---|---|---|
| no token, or a token whose family was revoked | `401` | Ktor's challenge — for the stream too: without a credential it is a 401, not an empty stream |
| anything unexpected while building the screen | `500` | `ApiError("internal_error", "something went wrong on our side")`, with the detail in the log and not on the wire |

There is no 404 on either route: a subscriber with no counters gets a screen that says so.

## Quirks

- **A balance the server could not read is left out, not drawn as zero.** Zero is a fact about an
  account and "we could not tell" is not, and a subscriber who reads the first when the second is true
  tops up money they already have.
- **A subscriber with no counters gets a banner and somewhere to go**, not an empty column: a screen
  that draws nothing is indistinguishable from one that failed to load.
- **The stream's channel is `Channel.UNLIMITED`.** The broadcaster offers into it and drops on a full
  channel, so a bound here would silently lose updates for a client that is merely slow.
  Unsubscription is in a `finally`, because the ordinary end of the loop is the client going away.
- **`KompotUpdateBroadcaster` must be started or it refuses to broadcast**, and the binding for it
  existed nowhere until the stand tried to start the application.
- **Nothing in the product moves a counter except the traffic simulator and a completed purchase.**
  The simulator is off unless `SIMULATE_TRAFFIC=true`, so on a default deployment this stream is
  correct and silent — which looks exactly like a broken one.
- **How the client cache and a live update interact is an open question**, `B-18`. The hypothesis on
  record is that a cold start shows a stale value for exactly one request.
