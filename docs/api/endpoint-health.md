---
id: endpoint-health
title: Health
type: api_endpoints
status: active
services:
  - konekt-server
contract_source:
  - konekt:server Application.kt — `baseModule`, mounted by `installKoreProbes`
  - kore:kore-ktor KoreRoutes — the four addresses, as constants rather than strings
---

# API: health

> Four routes now, and they were one. Until `konekt#32` the startup, liveness and readiness probes all
> pointed at `/health`, which answered while the process was alive — the single condition that cannot
> tell those three questions apart. **A pod whose database was unreachable reported itself ready and
> kept taking traffic.**

## Routes — all of them, no exceptions

| Method and path | Auth tier | Answers | Purpose |
|---|---|---|---|
| `GET /health/startup` | **public** | `200` `started`, or `503` naming what is outstanding | a latch: has the process finished starting |
| `GET /health/live` | **public** | `200` `alive`, or `503` with the reason | is the process wedged |
| `GET /health/ready` | **public** | `200` `ready`, or `503` naming the check and the age of its answer | are this pod's dependencies answering |
| `GET /health` | **public** | as `/health/live` | the alias the chart pointed at, kept so it could move a line at a time |

**Readiness is the only one that may answer for something outside the process**, and that is the
correction `konekt#32` made. The chart's own comment argued — correctly — that a probe must not read
the store, and then pointed readiness at the same route as liveness. The reasoning is right for
liveness: a liveness probe that reads the store restarts a pod for an outage a restart cannot fix.
Readiness is the opposite case; its failure is the cheap one, traffic stops and nothing is killed.

**It still does not block on the database.** `HealthRegistry` asks the checks on a loop of its own and
remembers the answers; the probe serves a remembered one. So `timeoutSeconds` in the chart never
decides the verdict, and a slow dependency cannot turn readiness into a second liveness.

**Measured rather than asserted**, against the stand on 2026-09-12: with Postgres stopped,
`/health/ready` answered `503 postgres unknown … past the 10s budget` while `/health/live` stayed
`200`; with Postgres back, readiness returned to `200` on its own.

**Their tier is a consequence of where they are installed, not of an entry anybody wrote.** They are
registered in `baseModule` (`server/src/main/kotlin/io/konekt/Application.kt`), which runs before
`configureAuthentication`, so they could not be inside `authenticate` even if someone wanted them
there — and a supervisor has no session to offer. `OpenApiDocumentTest` asserts the public set exactly,
so this is now stated somewhere rather than nowhere.

**They are the routes that are not in `konektRoutes`.** Every other route in the product sits in that
table with an `AuthTier` beside it; these are mounted directly, one function earlier. They are
deliberately **not** `/api/...`: they are not part of the product's API surface, and they are the
routes with no `@Resource` behind them — their addresses are `KoreRoutes` constants.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| all four | `server/src/main/kotlin/io/konekt/Application.kt` — `baseModule`, via `installKoreProbes` |
| the checks readiness reads | `server/src/main/kotlin/io/konekt/health/DatabaseHealthCheck.kt` |
| what calls them | `charts/konekt/templates/server.yaml` — the three probes; `deploy/Dockerfile` — the container `HEALTHCHECK`, on `/health/live` |

## Request and response bodies

None in, one word out — `started`, `alive`, `ready` — and on a refusal a sentence naming what is
missing. `ApplicationSmokeTest` asserts both that the three answer differently and that `/health` is
still there and is liveness.

## Errors

None it produces itself. A process that has stopped answering produces a connection failure rather
than a status code, which is the whole reason this route exists: the kernel accepts into the backlog
with no help from a hung process, so a TCP check would pass against a server that cannot serve.

## Quirks

- **The container healthcheck runs `bash`, and it had to be found the hard way.** `/bin/sh` in
  `eclipse-temurin:25-jre` is dash, which has no `/dev/tcp`; the check answered "Directory
  nonexistent" on every run and the container was permanently unhealthy while the process inside was
  serving. Nothing depended on it until `depends_on: service_healthy` did — an unhealthy container
  nobody waits for looks exactly like a healthy one.
- **The routes are installed by `baseModule`, which a test can install without a database.** The gates
  are defaulted for exactly that: `StartupGate()` with no named gates starts already OPEN, because a
  service that declares nothing to wait for has nothing to wait for. The real composition root names
  its gate — `workers` — and opens it after the sweeper, the relay and the broadcaster are running.
- **Readiness checks one dependency, and the broker is deliberately not it.** A broker that is away
  does not stop this server serving screens — the outbox holds the events and the relay retries — so
  checking it would take the pod out of rotation for a condition it can serve through, and `B-107`
  made a broker reconnect the ordinary case rather than an incident. The judgement lives in
  `DatabaseHealthCheck.kt` beside the checks, so reversing it is one edit in one place.
