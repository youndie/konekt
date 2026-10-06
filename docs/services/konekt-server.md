---
id: konekt-server
title: konekt server
type: service
status: active
repo_url: https://github.com/youndie/konekt
module: server
tech_stack: [Kotlin/JVM 25, Ktor 3.5.2 CIO, Koin 4.2.2, Exposed 1.5.0, PostgreSQL 18, Flyway, petich, booblik, kompot]
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

- **Generated schema:** [`docs/api/openapi.json`](../api/openapi.json), generated from the routes and
  compared by the build, so a route that drifts from it fails rather than documents itself wrongly
  (`B-23`). The reference a person reads is still [`docs/api/`](../api/).
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

**Migrations run as their own process, before any server serves.** `main` reads `KONEKT_MIGRATE_ONLY`, and
when it is set it migrates and exits without opening a port
(`server/src/main/kotlin/io/konekt/Application.kt`). In the stand that is the `migrate` service, which
the servers wait on with `condition: service_completed_successfully`; in a rolling deploy it is a job
that finishes before new pods roll. Two processes racing to migrate is the failure this removes, and
with Flyway's lock the race is a hang rather than an error.

**One petich engine for all three sagas, over one table.** Each saga is a definition keyed by its
type — `purchasePetich`, `topUpPetich`, `tariffPetich` — and the engine answers which definition owns
a row, so the sweeper is handed the one engine and a saga whose type has no definition is skipped
rather than rolled back by the wrong one. Until [#49](https://github.com/youndie/konekt/pull/49)
there were three engines and the sweeper picked one per saga. petich's phase timeouts are per engine,
so the raised `EXECUTION` bound applies to all three. `requireOutbox = true` is set explicitly: petich degrades
to a plain update when handed a repository that cannot store events, and the saga still completes
with correct state while nobody downstream is told.

**The workers are started from `ApplicationStarted` and cancelled on `ApplicationStopping`** — the
petich sweeper, the outbox relay, kompot's broadcaster, and the traffic chain when
`KONEKT_SIMULATE_TRAFFIC` is on. A binding is data and can be verified; a `start(scope)` call is control
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
- **The image carries a Leyden AOT cache, trained inside it** (`B-123`). `lib/app.aot` halves
  the time to `/health` on one core (4.4 s → 2.0 s) and the first request (510 → 240 ms), and
  leaves the next hundred alone — the JIT still compiles the request path. A cache is good only for
  the JDK build that trained it, so `scripts/aot-image.sh` trains it in a container of the image
  that ships, on the image's own JVM, with Postgres, the broker and the migrations from
  `deploy/compose.yaml` beside it (the application does not start without them, which is why this
  is not a Dockerfile stage), lays it over the image as one layer, and verifies it there under
  `-XX:AOTMode=on` — the mode in which a rejected cache is a failed step rather than three lines on
  stderr and a normal start. The publish workflow runs it before the push and the `verify` job runs
  the same verification against the pulled image; `make release-image` runs it on a laptop. The
  plugin behind it is [zavarnik](https://github.com/youndie/zavarnik): `server/build.gradle.kts`
  declares the readiness URL and the signed-in workload, and `installDist` carries the runner and
  its configuration in `lib/`. Measured in `research-measurements.md` §6a.
- **The JVM carries its own ceilings, and the container limit is not one of them** (`B-127`).
  `server.jvmOptions` sets `-XX:+UseSerialGC -Xmx64M -XX:MaxMetaspaceSize=128M
  -XX:ReservedCodeCacheSize=48M -XX:MaxDirectMemorySize=32M -Xss256k -XX:+ExitOnOutOfMemoryError`
  as `JAVA_TOOL_OPTIONS`, on the server and on the migration that runs before it, and the limit is
  `256Mi` against the `1Gi` this chart shipped with. The recipe is the one three other
  Kotlin/JVM services on this stack run, and two of its numbers are konekt's own: the code cache is 48M because 40 MB was
  committed under the reading profile at 200 rps, and metaspace is 128M because the AOT cache is
  what keeps metaspace at 7 MiB, and a cache the JVM refuses (`B-31` is that, in this cluster) puts
  those classes back into it. **Lowering the limit alone is not the same change and is worse than
  leaving it alone**: at a 256 MiB limit HotSpot's ergonomics take half of it for the heap rather
  than a quarter, so the measured peak was 250 MiB of 256 and the longest GC pause 260 ms against
  the unbounded JVM's 5. The chart refuses to render a limit below what the ceilings promise, which
  is the one arithmetic mistake here that arrives as `OOMKilled` and names nothing. Measured in
  `research-measurements.md` §9.
- **Probes:** a startup probe on `/health` every second for up to a minute, readiness every two
  seconds with no initial delay, liveness every twenty once startup has passed. The readiness probe
  used to wait five seconds before its first question, which made a pod ready at five whether the
  process answered at two or at four — the cache changed nothing a rollout could see until the
  probe was retuned (`B-123`).
- **The chart's version moves when the chart's shape moves**, and `scripts/chart_version.py` in the
  gate refuses a change under `templates/` or in `values.yaml` that leaves `version:` where it was.
  The number is what a deployment would pin, and while it stands still there is nothing to pin: a
  release tag fixes the BINARY, the templates that turn it into pods come from wherever the chart is
  read, a rollback to an older image renders under today's templates, and `helm --atomic` rolls back
  to a previous render that is not pinned either. It stood at `0.1.0` through `B-91`, which added a
  refusal to `templates/server.yaml` — a change that turns a render somebody could previously produce
  into a failure. Below `1.0.0` that moves the minor, so the chart is `0.2.0` (`B-99`). Prose in
  `Chart.yaml` moves nothing: a version bumped for a description teaches people to bump it without
  reading.
- **Chart:** `charts/konekt/`. It renders the server, a single-instance Postgres, the broker, the
  ingress, and the migration as the server pod's init container — a helm `pre-install` hook would run
  before the release's own objects, which on a first install means before the database exists. Four
  values have no default and stop the render rather than the pod: the hostname, the image tag, the
  JWT secret and the database password. Each of them, absent, produces a deploy that reports success.
- **Upgrading a release uses `--reset-then-reuse-values`, and `make deploy` is where that is
  written down.** `helm upgrade --reuse-values` reuses the previous release's *user-supplied* config
  INSTEAD of coalescing with the new chart's `values.yaml`, so every key the chart has gained since
  the last deploy renders **empty** — not defaulted. `B-106` is that in production: the three broker
  settings `B-100` added were declared in `values.yaml`, arrived at the container as
  `BOOBLIK_RETENTION_BYTES=`, retention was silently off, and nothing was red. `chart-check.sh` could
  not have seen it — it renders the chart, and a deployment is a different render. What the right
  flag does **not** do is re-examine the operator's own values: one they set and the chart has since
  removed is still carried forward, and no helm flag covers both cases. `make deploy` then runs
  `scripts/deploy-check.sh`, which compares the environment the cluster is running against the
  environment this chart renders with the same values — the only check here whose subject is a
  cluster rather than a file.
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

**One replica by default, more with the shared bus** ([B-137](../backlog/B-137-the-chart-allows-two-replicas.md)).
`charts/konekt/values.yaml` defaults `server.replicas: 1`; the template renders more only with
`kesh.enabled`, and refuses otherwise naming the bus — the one reason left that is a setting rather
than code. The table says what each worker does on two pods.

**The leader** is chosen by vojak — an advisory lock on konekt's own Postgres through `vojak-jdbc`
over the server's `DataSource` (`io.konekt.leader.Singletons`, B-135). Two elections: `singletons` for
the outbox relay and the usage consumer, which every replica joins, and `simulator`, which only a
replica with `KONEKT_SIMULATE_TRAFFIC` joins — the stand runs a second server with it off, and with one
election that server won it and nothing published usage. The pool is eleven; a held leadership keeps
one connection for as long as it lasts. A replica
that does not lead runs none of them and serves every route. A stopped pod closes the election in the
`workers` shutdown participant, so the next leader takes over within a poll; a killed one costs
vojak's `localLease` (7.5 s) of nobody leading — the price of never having two (vojak's D12). The
holder shows in `pg_stat_activity.application_name` as `vojak <pod name>`.

| Worker | Started by | With two pods |
|---|---|---|
| `UsageChain` — applies whatever arrives on `usage` | on the **leader** ([B-135](../backlog/B-135-singletons-run-on-the-leader.md)) | only the leader reads. Were two to read anyway — a stalled leader finishing a batch after it was replaced — the position in `consumer_position` moves in the decrements' transaction, only from the offset the batch was read at, and the loser rolls back (`B-134`, `UsagePositionTest`) |
| `TrafficChain` — the traffic simulator | `KONEKT_SIMULATE_TRAFFIC`, on the **leader** (B-135) | one publishes; the others run no simulator. Before B-135 each published its own fictional usage, so allowances drained at a multiple of the configured rate, and the chart refused the combination; since [B-137](../backlog/B-137-the-chart-allows-two-replicas.md) it renders |
| `SuspendedPetichSweeper` — compensates abandoned sagas, and since [B-130](../backlog/B-130-an-allowance-has-no-name-to-be-taken-back-by.md) carries forward the stranded ones (a saga left PROCESSING for `MockPaymentGateway.STRANDED_AFTER`, two minutes, by a process that died) | always | both walk the same sagas; one of them does the work. petich claims a saga with one write on its own row before touching it — on the expiry queue the move to COMPENSATING, on the stranded queue a version bump — and the sweeper whose write loses skips the saga (`TwoReplicasSweepTest` forces both to read before either writes). konekt's own claim table, `ClaimedSweep` ([B-92](../backlog/B-92-the-sweeper-still-does-not-claim-a-saga.md)), predated that and was removed by [B-132](../backlog/B-132-claimed-sweep-is-a-second-claim.md). The money does not rest on the claim: a unique index on `ledger_entry (order_id, kind)` makes a second compensation a no-op (`B-64`), and every member a re-drive runs again lands once — `Provision` (`ProvisionByOrderTest`), and the members before a confirmation in the purchase and the tariff change ([B-131](../backlog/B-131-a-stranded-first-pass-is-rolled-back.md), `StrandedFirstPassTest`, `StrandedTariffChangeTest`) |
| `OutboxRelayWorker` — publishes outbox rows | on the **leader** (B-135) | one publishes. petich's relay has no claim — no `FOR UPDATE`, no `SKIP LOCKED` — and two of them published every row twice: 40 records for 20 rows, measured by `SingletonsTest` with the election removed. Delivery stays at-least-once, and the event id is stable across redeliveries |
| `KompotUpdateBroadcaster` — the realtime bus | always | each holds its own SSE connections. **With `KONEKT_REALTIME_URL`** (`kesh.enabled` in the chart, always on the stand) every replica publishes through kesh and hears every other's, so a push produced on one pod reaches a subscriber attached to another (`B-136`, `SharedUpdateBusTest`). Without it the bus is in memory and that push is lost silently — which is why the chart refuses a second replica with `kesh.enabled` off (B-137; [B-91](../backlog/B-91-a-second-replica-loses-live-updates.md) refused it outright). A kesh that is down costs live updates and nothing else: the subscription retries every second, a publish gives up after two |

One combination is refused by the chart: more than one replica with `kesh.enabled` off, because the
failure it causes — a screen that does not refresh — is silent. Until B-137 it refused the simulator
above one replica and, since [B-91](../backlog/B-91-a-second-replica-loses-live-updates.md), any second
replica at all; B-134, B-135 and B-136 removed those reasons one by one. `scripts/chart-check.sh`
renders both sides of the remaining refusal.

**Two replicas are proved on a cluster, not only in one test JVM** (`B-138`):
`scripts/rolling-check.sh two-replicas` installs this chart in a kind cluster on the build machine —
two server replicas, kesh, the simulator off — and `:e2e:twoReplicasCheck` publishes usage a megabyte
at a time while it kills the leader pod (`--grace-period=0 --force`, no drain) and then rolls the
deployment. The verdicts are exact: every event applied once and none lost (45 events, 45 MB), at most
one `vojak <pod>` session at every 200 ms sample, and a client on each pod hearing the updates the
other applied. Its control is the same release with the bus in memory — one replica scaled to two past
the chart's refusal — where the client on the follower hears nothing and the check fails. More than
one replica is rolled (`maxSurge: 1`, `maxUnavailable: 0`); one is still recreated.

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

**Every variable carries the `KONEKT_` prefix and is declared in one schema** — `KonektSchema` in
`server/src/main/kotlin/io/konekt/KonektConfig.kt`, read once at startup through kore's
`ConfigSchema` (`konekt#35`). The file is the list. Do not copy it here; what is worth stating is
what the schema does that eighteen scattered `System.getenv` calls did not:

- `KONEKT_DB_URL`, `KONEKT_DB_USER`, `KONEKT_DB_PASSWORD`, `KONEKT_JWT_SECRET` are **required**:
  absent means a process that will not start, rather than a route that fails later under a user. Every
  problem is reported at once, so a deployment being configured for the first time is one round trip
  rather than one restart per missing value.
- **A variable under the prefix that the schema does not declare refuses the start**, and the message
  names the declared variable it is probably a misspelling of. `KONEKT_SIMULATE_TRAFIC` used to be a
  stand that silently did not simulate.
- **A value that does not parse refuses the start** rather than falling back.
  `KONEKT_PAYMENT_MOCK_DELAY_MS=1s` used to be zero.
- **An agent is both its variables or neither.** `KONEKT_TRACY_ENDPOINT` without `KONEKT_TRACY_KEY` is
  a refusal naming the missing half, and all three agents are checked in the same pass. That rule and
  the agent keys themselves are **kore's**, spliced into konekt's schema as a list — kore publishes
  keys rather than a schema of its own, because a schema owns a prefix and two prefixes would mean a
  variable that is unknown to one scope and declared by the other.
- **`KONEKT_SERVICE` is required and has no default.** There is no registration step in any of the
  three agents, so the service name IS the identifier: a typo does not fail, it files everything under
  a phantom service that looks healthy and receives nothing. Every path that runs this binary names
  it, the migrate container included.
- **`KONEKT_TRACY_SAMPLE_RATE` is 1.0 here and would not be in production.** It decides whether a
  request's WHOLE pending trace is kept, not how many spans survive inside one — below the rate a
  trace is warnings and entity references with nothing behind them. tracy's own default is 0.01; this
  deployment promises one purchase visible whole by its order id.
- Every switch is opt-in by the exact string `"true"` — `KONEKT_DEV_REVEAL_OTP`,
  `KONEKT_SIMULATE_TRAFFIC`, `KONEKT_MIGRATE_ONLY`. Anything else is off, a misspelling included, so
  a security switch cannot ship open. `KONEKT_PAYMENT_MOCK_MODE` is the same shape: anything other
  than `"decline"` approves.

**`./bin/server --print-config` prints what a deployment thinks it is configured as**, with each
value's origin — the environment or the default — and secrets masked. It is a flag rather than a
route because the question is asked most often *because* the process will not start: it prints what
it did resolve and exits non-zero with the problems the start would have refused on.

**The host's variables in `deploy/compose.yaml` are deliberately NOT prefixed.**
`SIMULATE_TRAFFIC=false docker compose … up` still works; compose interpolates it into the
container's `KONEKT_SIMULATE_TRAFFIC`. The prefix belongs to the server process, not to the shell
that starts a stand. The same split applies to the client, whose own `TRACY_ENDPOINT` and
`KATCHER_ENDPOINT` are a different process's settings and stay as they are.

**`enableServiceLinks: false` in the chart is load-bearing, not tidiness.** For every Service in the
namespace the kubelet injects `<NAME>_SERVICE_HOST`, `<NAME>_PORT` and a `<NAME>_PORT_<port>_TCP*`
group named after the **Service** — and this chart's server Service is `{{ .Release.Name }}`, which
is `konekt`. Left on, the pod is handed `KONEKT_PORT=tcp://10.43.x.x:8080` where an integer is
declared, plus four undeclared names under the prefix, and does not start.
`KonektConfigSchemaTest` is what keeps the line in the chart.

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
