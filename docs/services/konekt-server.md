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

**A screen is drawn from a view, and the render step looks nothing up.** `data → use case → view →
render`: the route reads the principal and calls one use case, the use case answers with everything
the screen needs already resolved — a tariff's title rather than its id, one instant rather than a
clock — and the screen turns that into components. Half the server was already built this way
(`TopUpView`, `OrderView`, `EsimWizardView`); `B-96` named the rule and finished the other half.

Three things follow, and `ScreensLookNothingUpTest` enforces all three:

- **a screen file imports no repository, catalogue or use case**, so it cannot answer a different
  question than the one the use case answered;
- **a screen never reads the clock.** The instant is on the view, taken once per response. Both card
  factories used to hold a `KonektClock` and read it per card, so one screen could caption five cards
  against five instants;
- **a view type never appears in a `*-shared-api` module.** The wire is the component tree; a view on
  the wire would make the client depend on how the server split its presentation.

The rule is the LOOKUP, not the arity. `PlansScreen.build(plans: List<Plan>)` decides nothing and
needs no view of its own, and a card factory is a renderer a screen may hold.

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
- **Published image:** `ghcr.io/youndie/konekt-server:<tag>`, built and pushed by
  `.github/workflows/publish-image.yaml` when a `v*` tag is pushed. The push is in CI rather than in
  a Makefile target because the right to write to the registry is what CI has and a laptop does not
  — `B-47`. The same workflow then PULLS the tag back and drives the whole e2e suite through it,
  which is the only check here whose subject is an artefact rather than a working tree.
- **Chart:** `charts/konekt/`. It renders the server, a single-instance Postgres, the broker, the
  ingress, and the migration as the server pod's init container — a helm `pre-install` hook would run
  before the release's own objects, which on a first install means before the database exists. Four
  values have no default and stop the render rather than the pod: the hostname, the image tag, the
  JWT secret and the database password. Each of them, absent, produces a deploy that reports success.
- **Where the environment lives:** nowhere in this repository, and that is the split. The chart
  carries the SHAPE — what runs, what may not be reached, what stops the render — and a deployment's
  own addresses, keys and image tag are values an operator keeps beside their cluster. A chart that
  shipped an address would be a chart with an opinion about somebody else's network.
- **Using a deployed instance.** There is no browser surface: the client is Compose on a desktop or
  a phone, so it is pointed at the deployment with `KONEKT_URL`. Signing in needs the one-time code,
  and with `dev.revealOtp` off — the default, and the security property — the code reaches only the
  server's log, at WARN, from the mock delivery that stands in for an SMSC. Reading a log is a
  different permission from being on the internet, which is the whole of why that switch defaults
  closed. `B-48`.
- **The broker is closed by a NetworkPolicy rather than by the absence of a `ports:` line.** In
  compose that absence is its whole security model — it speaks a plaintext protocol with neither TLS
  nor authentication, both deliberately absent — and a namespace gives nothing for free: a ClusterIP
  Service is reachable by every pod in the cluster until something says otherwise.

## 5a. What runs per replica

**This build is a single-instance deployment**, and the honest way to say so is a table of what a
second pod would do rather than a sentence saying not to. `charts/konekt/values.yaml` defaults
`server.replicas: 1`; horizontal scale is a non-goal in
[reference-scope](reference-scope.md).

| Worker | Started by | With two pods |
|---|---|---|
| `UsageChain` — applies whatever arrives on `usage` | always, on `ApplicationStarted` | **each applies every event**: booblik keeps no consumer offsets and there is no group, so a 25 MB decrement becomes 50 MB. Nothing in any log says so |
| `TrafficChain` — the traffic simulator | `SIMULATE_TRAFFIC` | each publishes its own fictional usage, so allowances drain at a multiple of the configured rate. **The chart refuses this combination outright** |
| `SuspendedPetichSweeper` — compensates abandoned sagas | always | both walk the same sagas and both compensate; the money is correct because of a unique index on `ledger_entry (order_id, kind)` (`B-64`) and the second one now does nothing. The wasted work is [B-92](../backlog/B-92-the-sweeper-still-does-not-claim-a-saga.md) |
| `OutboxRelayWorker` — publishes outbox rows | always | both read the same pending rows; delivery is at-least-once by design and the event id is stable across redeliveries, so a consumer keyed on it copes |
| `KompotUpdateBroadcaster` — the realtime bus | always | **in memory**, so a push produced on one pod never reaches a subscriber attached to the other. The screen does not refresh, nothing is logged, and the next ordinary fetch shows the right state — which is the hardest symptom to attribute. [B-91](../backlog/B-91-a-second-replica-loses-live-updates.md) |

Only one of the five is refused by the chart, and only because it drains allowances on a timer rather
than on traffic. The rest are stated here because a default that is right and a failure mode that is
silent is exactly the combination this repository fails builds over.

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

- **A subscriber's connection dying used to file a crash report.** Ktor's CIO wraps a broken pipe on
  the realtime stream in a `ChannelWriteException` — a `Throwable` like any other, so it reached
  `StatusPages`' catch-all, was logged at ERROR, and became a katcher group. Which endings do it was
  MEASURED and is narrower than it first looked: killing the desktop client mid-push filed one,
  closing its window did not, because a graceful close ends the read side between frames. So it is
  the ungraceful half — a closed laptop, a phone off the network — raced against the push cadence.
  Still worth silencing: it is a report about somebody's network filed under this product's defects,
  and reports nobody can act on are what teach an operator to stop reading the ones they can. The
  branch is narrow on purpose — not `IOException`, which would also silence a failure talking to the
  database.
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
- **An applied migration is immutable, and its comments are part of it.** Flyway checksums the whole
  file. V11 was deployed, then its comment was corrected to record a recovery procedure that had been
  measured — no SQL changed — and the next release could not start: the `migrate` init container
  crash-looped on validation, the deployment never became available, and helm rolled back after its
  ten-minute timeout with a message about readiness. Nothing in that chain names the edit. Recovery on
  a contour that already ran the version is `flyway repair` (or deleting its `flyway_schema_history`
  row) before the pod will boot. `AppliedMigrationsAreImmutableTest` now holds a checksum per file so
  the edit stops on a laptop, and `MigrationChecksumOracleTest` checks those numbers against what real
  Flyway writes.
- **A migration that refuses needs three steps to retry, not one.** V11 builds a unique index
  `CONCURRENTLY`, so on a database with a duplicate ledger movement it fails — deliberately: the
  duplicates are money that was given away. The recovery was measured rather than reasoned about:
  reconcile the rows (a decision, not a `DELETE`), then `flyway repair`, then migrate. **The middle
  step is the one that is easy to miss**: a non-transactional migration that fails is recorded as
  failed, and Flyway then stops at validation before executing any SQL — so the `DROP INDEX IF EXISTS`
  at the top of V11, which clears the invalid index a failed concurrent build leaves behind, is
  necessary and never reached on its own.
- **Actions are not generated.** kompot's KSP processor covers components; `KompotAction` subclasses
  are registered by hand in `esimActionsSerializersModule`. Leaving one out compiles, starts and
  draws every screen — and fails the decode on the one request the action exists for.
