---
id: konekt-server
title: konekt server
type: service
status: active
repo_url: https://github.com/youndie/konekt
module: server
tech_stack: [Kotlin/JVM 25, Ktor 3.5.2 CIO, Koin 4.2.2, Exposed 1.4.0, PostgreSQL 18, Flyway, petich, booblik, kompot]
owner: unassigned
depends_on:
  - PostgreSQL 18
  - konekt-broker
  - SM-DP+ (outside the boundary, mocked in-process)
  - payment provider (outside the boundary, mocked in-process)
  - SMSC (outside the boundary, mocked in-process)
publishes:
  - purchase.completed
  - purchase.reversed
---

# konekt server

> Everything below was read out of the files named in §2a on 2026-08-25. Where a fact could not be
> read out of the code it says so; nothing here is inferred from the backlog.

## 1. Responsibility

The one process that serves the subscriber account. It owns every table in the product — subscribers
and accounts, the ledger, one-time codes and session families, entitlements, eSIM profiles, usage
counters, wizard runs, and petich's saga and outbox tables — and it **builds the screens**: a client
receives a component tree, never a layout decision.

What it deliberately does not do:

- **It does not talk to any real external system.** The BSS/OCS, the SM-DP+, the payment provider and
  the SMSC are all behind interfaces with in-process mocks
  (`feature/purchase-server-data/.../MockPaymentGateway.kt`,
  `feature/esim-server-data/.../MockSmDpPlus.kt`,
  `feature/purchase-server-data/.../StaticPlanCatalog.kt`,
  `feature/auth-server-data/.../OtpDeliveryImpl.kt`). The boundary of the system stops there, which
  is why the development route in [endpoint-auth](../api/endpoint-auth.md) exists at all.
- **It does not format money on the client's behalf twice.** Only this service formats `Money`
  (`shared/server-common/src/main/kotlin/io/konekt/money/MoneyFormat.kt`); the client renders a
  string. See [research-stack](../research/research-stack.md) D15.
- **It does not migrate its own schema while serving.** Migrations are a separate run of the same
  image — see §3.

## 2. API contracts

- **Generated schema:** none. `B-23` is the item, and until it closes the route reference a person
  reads is [`docs/api/`](../api/).
- **Contracts:** the `@Resource` classes in `feature/<name>-shared-api/`, plus
  `feature/realtime-shared-api/src/commonMain/kotlin/io/konekt/feature/realtime/shared/api/RealtimeStream.kt`
  for the one path that cannot be a `@Resource`.
- **Auth tiers:** the mount-to-gate table is `server/src/main/kotlin/io/konekt/Application.kt`, and it
  is the only place that decides them. Every endpoint document repeats the tier per route; see the
  quirk in §8 about what checks it.

## 2a. Code anchors

| File | What is there |
|---|---|
| `server/src/main/kotlin/io/konekt/Application.kt` | the composition root: plugins, Koin modules, the auth tiers, the workers, the application's `Json` |
| `server/src/main/kotlin/io/konekt/KonektConfig.kt` | every environment variable this process reads |
| `shared/db/src/main/kotlin/io/konekt/db/DatabaseFactory.kt` | the datasource, the Flyway run, the lock settings |
| `shared/db/src/main/resources/db/migration/` | the migrations Flyway applies |
| `shared/server-common/src/main/kotlin/io/konekt/http/StatusPages.kt` | every refusal becoming a status code |
| `server/src/main/kotlin/io/konekt/events/EventTopics.kt` | which event type reaches which broker topic |
| `deploy/compose.yaml` | the stand: Postgres, broker, migrate job, two servers |
| `deploy/Dockerfile` | the image and its healthcheck |

## 3. How it is built

**Migrations run as their own process, before any server serves.** `main` reads `MIGRATE_ONLY`, and
when it is set it migrates and exits without opening a port
(`server/src/main/kotlin/io/konekt/Application.kt`). In the stand that is the `migrate` service, which
the servers wait on with `condition: service_completed_successfully`; in a rolling deploy it is a job
that finishes before new pods roll. Two processes racing to migrate is the failure this removes, and
with Flyway's lock the race is a hang rather than an error.

**One petich engine per saga type, sharing one table.** The sweeper resolves the owning engine per
saga rather than taking one, because rolling a purchase back with another type's interceptor list
would run the wrong compensations or none. `requireOutbox = true` is set explicitly: petich degrades
to a plain update when handed a repository that cannot store events, and the saga still completes
with correct state while nobody downstream is told.

**The workers are started from `ApplicationStarted` and cancelled on `ApplicationStopping`** — the
petich sweeper, the outbox relay, kompot's broadcaster, and the traffic chain when
`SIMULATE_TRAFFIC` is on. A binding is data and can be verified; a `start(scope)` call is control
flow and cannot, which is why `WorkersAreStartedTest` reads this file as text.

**The engine is CIO.** The load-bearing endpoint is SSE — many long-lived, mostly idle streams — which
is the profile a coroutine-per-connection engine is shaped for. See
[research-stack](../research/research-stack.md) D19.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Database | PostgreSQL 18 | every table in the product; Testcontainers in tests, never H2 |
| Service | [konekt-broker](konekt-broker.md) | the outbox relay publishes there; the traffic simulator produces and consumes `usage` |
| External | SM-DP+ | issuing eSIM profiles — mocked in-process |
| External | payment provider | settling a purchase — mocked in-process |
| External | SMSC | delivering a one-time code — mocked in-process, and nothing is ever sent |
| External | BSS/OCS | the plan catalogue and the add-on price list — static, in-process |

## 5. Infrastructure and deploy

- **Image:** built by `deploy/Dockerfile`, which copies in the distribution that
  `./gradlew :server:installDist` has already produced. The distribution is built **outside** the
  image on purpose — a Gradle stage inside would be a second way of building the thing CI already
  tested, and it re-downloads the toolchain on any cache miss. Forgetting the step gives a container
  running whatever was built last time, which is the most confusing failure this stand can produce.
- **Base:** `eclipse-temurin:25-jre`. A 21 runtime fails at exec with `UnsupportedClassVersionError`,
  not at build.
- **Health:** `GET /health` → `200 ok`. The container healthcheck runs it through `bash` and not `sh`
  — see §8.
- **Observability:** all three agents, and each measured at the COLLECTOR rather than at its own
  configuration — metrik as latency per route, tracy as a purchase findable by `orderId`, katcher as a
  report when a route throws. All three answer a missing key or an unreachable collector by doing
  nothing, so a deployment that meant to be observed and is silent looks exactly like one that is
  working; `ObservabilityScenarioTest` is what tells them apart. A half-configured agent is refused at
  startup rather than switched off quietly.
- **Version:** none on the wire. The release reaches the collectors through `RELEASE` and appears on
  every record; nothing serves it over HTTP.

## 6. Local setup

```bash
make stand-up     # ./gradlew :server:installDist, then docker compose up -d --build --wait
make e2e          # ./gradlew :e2e:e2e — needs the stand already up
make stand-down
```

The stand runs Postgres, the broker, the migrate job, the server on `8080` and **a second server on
`8081` whose payment mock refuses** (`server-declining`). The mode is read once at startup, so the
compensated branch of a purchase is a service rather than a switch.

## 7. Configuration

Every key is read in one place — `server/src/main/kotlin/io/konekt/KonektConfig.kt` — and the file is
the list. Do not copy it here; what is worth stating is the shape of the defaults:

- `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` are **required**: absent means a process that will
  not start, rather than a route that fails later under a user.
- Every switch is opt-in by the exact string `"true"` — `DEV_REVEAL_OTP`, `SIMULATE_TRAFFIC`,
  `MIGRATE_ONLY`. An unset or misspelled variable means off. `PAYMENT_MOCK_MODE` is the same shape:
  anything other than `"decline"` approves.

## 8. Quirks

- **The auth tier of a route is asserted by nothing below the stand.** `konektRoutes` in
  `Application.kt` pairs an `AuthTier` with each group of routes, and `mountKonektRoutes` is what
  turns `AuthTier.USER` into `authenticate(AUTH_JWT)`. Nothing checks that a route is in the right
  group: every route test installs an authentication provider of its own (`bearer("test")` in
  `RealtimeStreamTest`, `EsimWizardRoutingTest`, `SessionRotationTest`), and the e2e suite always
  sends a bearer token — so a route moved from one group to the other would keep every test green.
- **`/health` is the one route outside the route table.** It is registered in `baseModule`, which
  runs before `configureAuthentication`, so it could not carry a tier even if someone wanted it to.
  The tier is right — it exposes a two-letter string — and anything that reads `konektRoutes` in order
  to describe this server will not see this route.
- **The container healthcheck needs `bash`, and needed it before anyone noticed.** `/bin/sh` in the
  image is dash, which has no `/dev/tcp`, so the check answered "Directory nonexistent" on every run
  and the container was permanently unhealthy while the process inside was serving. Nothing depended
  on the healthcheck until `depends_on: service_healthy` did.
- **A `KompotUpdateBroadcaster` that is not started refuses to broadcast**, and the binding for it
  existed nowhere until a stand tried to start the application. Four defects of that shape survived
  191 green tests, because every test below the stand builds its own object graph.
- **The saga's storage format depends on the application's `Json`.** `classDiscriminator = "type"`
  and the `@SerialName` on `PurchasePayload` are what make an already-persisted saga readable;
  changing either is a data migration, not a refactor.
- **Actions are not generated.** kompot's KSP processor covers components; `KompotAction` subclasses
  are registered by hand in `esimActionsSerializersModule`. Leaving one out compiles, starts and
  draws every screen — and fails the decode on the one request the action exists for.
