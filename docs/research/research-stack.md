---
id: research-stack
title: konekt — the technical stack and the module layout
type: research
status: active
date: 2026-08-25
---

# Research: the technical stack and the module layout

[research-architecture](research-architecture.md) settles what the six toolkits can and cannot do.
This document settles what is built on top of them: the versions, the module layout, the layer rules
inside a module, and the four types that have to exist before any feature does.

Same rule as its neighbour — every version below was read in a registry today, not recalled. That is
not pedantry: three of the versions a reasonable person would have written from memory are wrong, and
one of them (`ktlint-cli`) is wrong in the direction that silently changes formatting.

---

## 1. Verified facts

### 1.1 The versions, read on 2026-08-25

Maven Central `maven-metadata.xml` per coordinate, the Gradle version index, and the Gradle Plugin
Portal.

| What | Version | Where verified |
|---|---|---|
| Gradle | `9.7.1` | `services.gradle.org/versions/all` — 9.7.1 is the head of the 9.7 line; 9.7.0 exists and is one patch behind |
| JVM toolchain | `25` | required, not chosen: kompot and petich tag variants `org.gradle.jvm.version = 25` and Gradle refuses to build against them from a lower toolchain |
| Kotlin | `2.4.10` | the version kompot `0.30.0` and petich `0.1.0` are built with |
| Ktor | `3.5.2` | `kompot/gradle/libs.versions.toml`; `io/ktor/ktor-server-resources-jvm` is published at the same version |
| ktlint Gradle plugin | `14.2.0` | `plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint/…` |
| ktlint CLI | `1.8.0` | `com/pinterest/ktlint/ktlint-cli` |
| Koin BOM | `4.2.2` | `io/insert-koin/koin-bom`; `koin-ktor` and `koin-logger-slf4j` at the same version |
| Exposed | `1.4.0` | `org/jetbrains/exposed/exposed-core` |
| Flyway | `13.3.0` | `org/flywaydb/flyway-core`, and `flyway-database-postgresql` at the same version |
| HikariCP | `7.1.0` | `com/zaxxer/HikariCP` |
| PostgreSQL JDBC | `42.7.13` | `org/postgresql/postgresql` |
| MockK | `1.14.11` | `io/mockk/mockk` |
| Turbine | `1.2.1` | `app/cash/turbine/turbine` |
| Testcontainers | `1.21.4` | `org/testcontainers/postgresql` |
| Logback | `1.6.3` | `ch/qos/logback/logback-classic` |
| KSP | `2.3.11` | matched to Kotlin `2.4.10` |
| AGP | `9.3.1` | with the `com.android.kotlin.multiplatform.library` plugin, not the old `library` plugin |

**Consequence 1.** `search.maven.org`'s solr index is stale — it answers `ktlint-cli 1.6.0`,
`mockk 1.14.3`, `exposed-core 1.0.0-beta-4`, `flyway-core 11.8.2`. Every one of those is behind the
repository's own `maven-metadata.xml`. Ask `repo1.maven.org` for metadata, not the search API.

**Consequence 2.** The ktlint **CLI** version is a bare version string in the catalogue, not a
coordinate, so nothing resolves it and nothing renovates it unless it is told to. It carries
`# renovate: datasource=maven depName=com.pinterest.ktlint:ktlint-cli` above it, and the plugin is
pinned to it explicitly rather than left to the plugin's default — otherwise the style shifts when the
plugin is bumped, which is exactly the moment nobody reads the diff.

### 1.2 Exposed is JVM-only, so it decides a module's plugin

Read in the published Gradle module metadata of `exposed-core:1.4.0`: the variants are
`apiElements` and `runtimeElements`, both `org.gradle.kotlin.platform.type = jvm`. There is no
`common` metadata variant.

**Consequence.** A module that touches Exposed is `kotlin("jvm")`, not `kotlin("multiplatform")` with
a single `jvm()` target. Both compile; the first avoids the resolution friction a multiplatform
consumer meets against a JVM-only producer. `petich-postgres` is the same shape for the same reason.
This is what makes the data layer the one layer of a feature that cannot be common code.

### 1.3 MockK publishes `common` and `jvm`, and nothing else

Read in `mockk:1.14.11`'s module metadata: two platform types, `common` and `jvm`.

**Consequence.** MockK is usable from `commonTest` **only in a module whose every target is
JVM-family**. In a module that also targets iOS or Android-native it will not resolve, and the failure
arrives at dependency resolution rather than at compile. So:

| Module kind | Doubles |
|---|---|
| `-server-domain` (`jvm()` only) | MockK — the repository interface is mocked and the use case is the subject |
| `-server-data` (`kotlin("jvm")`) | **no doubles** — a real Postgres, see §2.4 |
| `-shared-*`, `-client-*` (jvm + ios + android) | hand-written fakes, `object : XRepository { … }` |

### 1.4 What one earlier build of this idea did, and where it cost

An earlier, closed build of a backend-driven banking demo on the same two toolkits is the nearest
thing to prior art, and three of its choices are worth carrying and two are worth not.

Worth carrying:

- **A feature is a vertical of modules**, not a package: `<name>-shared-api`, `-shared-domain`,
  `-server-domain`, `-server-data`, `-client-domain`, `-client-data`, `-client-ui`. The compiler then
  enforces the layering that a package convention only asks for politely.
- **`api` versus `implementation` is decided per dependency and commented.** A type that stands in a
  public signature is `api`; anything else is `implementation`. Getting this wrong hands a consumer a
  signature they cannot name, and it compiles.
- **The server CIO engine collides by name with the client CIO engine.** `io.ktor.server.cio.CIO`
  needs an import alias wherever an `HttpClient` is also constructed, or `embeddedServer` silently
  receives the client engine and the file does not compile.

Worth not carrying:

- **Endpoint paths as `object Endpoints { const val … }`.** It removes the string from the routing and
  from the client, which is most of the value — but a path with a parameter still appears twice, once
  as a Ktor template (`"/v1/api/kyc/session/{id}/document"`) and once as a builder function
  (`fun sessionDocument(id: String) = "…/$id/document"`). Two strings that must agree, with nothing
  checking that they do. `ktor-resources` removes both.
- **Money formatted by a free function taking `Long` minor units.** The same function was written
  twice — once on the server for the screen, once in a client view model — each dividing by a
  hard-coded `100`. That is the argument for a type rather than a helper, and §2.5 takes it.

Also read there and worth knowing: that build wired its server graph by hand, with no DI container at
all. Koin on a Ktor server is therefore new here rather than inherited, and the reference for it is
the toolkit-side family (`koin-ktor` with `by inject<T>()` inside `Route.xxxRoutes()`), not that one.

---

## 2. Decisions

### D11. Gradle 9.7.1 on a Java 25 toolchain *(deviation: 9.7.1, not the 9.7.0 that was asked for)*

9.7.0 exists and works; 9.7.1 is the head of the same line as of today and a wrapper pinned to a
superseded patch in a repository with no history is a bump waiting to happen. One character in
`gradle-wrapper.properties` if the exact 9.7.0 is wanted.

Java 25 is not a preference — see §1.1. The toolchain is declared once in a convention plugin, because
Gradle tags variants with `org.gradle.jvm.version` and refuses to build a module on 21 against a
dependency on 25, so one module left behind fails in a way that names the dependency rather than the
module.

### D12. A feature is a vertical of modules, and the vertical is four wide, not seven

Decision: per feature, `feature/<name>-shared-api`, `feature/<name>-server-domain`,
`feature/<name>-server-data`, `feature/<name>-client`. The seven-module split of §1.4 is available and
is taken **only when a feature earns it** — when the client grows logic worth testing without Compose,
or when a domain rule genuinely belongs to both sides.

Why:

- the layering the compiler enforces is worth its cost: a `-server-domain` that cannot see Exposed
  cannot accidentally take a dependency on it, which is the whole point of the interface;
- seven modules × seven features is forty-nine Gradle projects for a build that also compiles Android
  and iOS. Configuration time is a real budget and this project has no build-cache story yet;
- four is the smallest split that keeps the three rules that matter — the wire is shared, the domain
  cannot see the database, and the data layer is JVM-only (§1.2);
- the price: a feature that later needs `-client-domain` gets a module added mid-flight, which is a
  `settings.gradle.kts` line and a move. That is cheaper than the reverse.

### D13. `ktor-resources`, and no endpoint path exists as a string

Decision: `@Resource` classes live in `<name>-shared-api` and are the only place a path is written.
`ktor-server-resources` on the server, `ktor-client-resources` in the client.

Why: §1.4 — the constant-based alternative still duplicates a parameterised path between a Ktor
template and a builder function, and a renamed segment is a runtime failure in the user's hands rather
than a compile error. The check before a pull request is a `grep` for `/api/` outside
`*-shared-api`: it must return nothing.

### D14. Clean architecture inside a feature, with the route as the thinnest layer

Decision, per the project's `server-feature-impl` conventions:

```
feature/<name>-shared-api/     Endpoints (@Resource), request/response DTO, wire enums
feature/<name>-server-domain/  <Name>Repository (interface), <Verb><Name>UseCase, policies
feature/<name>-server-data/    <Name>Tables, Exposed<Name>Repository, <Name>Routing, <Name>Screens
feature/<name>-client/         renderers, the client-side source
```

- a use case is one class, one operation, `suspend operator fun invoke(params): Result<R>`;
- a route parses, calls a use case through `by inject<T>()`, and answers `.getOrThrow()`;
- a plain read with no side effect may go straight to the repository — a use case per getter is
  ceremony;
- repositories are `single`, use cases are `factory`.

`suspendRunCatching` is written once, in a shared place, and used instead of `runCatching`: plain
`runCatching` swallows `CancellationException` and breaks coroutine cancellation in a way that shows
up as a request that will not stop.

**`singleOf(::XImpl)` resolves every constructor parameter through the container, including ones with
a Kotlin default value** — the default is ignored, and a parameter whose type has no binding throws
`NoDefinitionFoundException` at runtime while the compiler says nothing. A repository with a
self-sufficient defaulted parameter is registered with an explicit lambda instead.

### D15. `Money` is a type, and the client never formats it

Decision: a `Money` value type in the shared domain, carrying minor units and a currency, with the
currency's exponent on the currency rather than assumed. Arithmetic on it; no `Double` anywhere near
money; no free `formatMoney(Long, String)`.

Why:

- §1.4 — the helper form was written twice, both times dividing by a hard-coded `100`. A hundred is
  right for the rouble and wrong for the dinar and for the yen, and there is nothing in a `Long` that
  says which one it is;
- backend-driven UI removes the second copy by construction: the server builds the screen, so the
  server formats and the client renders a `text`. A client that cannot format money cannot format it
  inconsistently. That makes the formatter server-side and single;
- the price: `Money` crosses the wire inside DTOs, so its serial form is part of the contract and is
  fixed once — minor units plus an ISO code, never a formatted string, because a formatted string is
  unusable for arithmetic on the other side and is the shape that invites a client to re-parse it.

Not covered: multi-currency arithmetic. Adding two `Money` of different currencies throws; the product
is single-currency per account and a conversion is a domain operation, not an operator.

### D16. Repository tests run against a real Postgres, not against H2 and not against a mock

Decision: Testcontainers with the same Postgres major the deployment runs. MockK is for the use-case
layer, where the repository interface is the seam.

Why:

- mocking Exposed proves nothing — the defect a repository test is for lives in the SQL, and a mock
  returns whatever the test wrote into it;
- H2 in Postgres compatibility mode is the cheaper option and diverges on exactly what this build
  leans on: `ON CONFLICT`, `SELECT … FOR UPDATE` (petich's optimistic locking sits next to it), and
  `jsonb` columns, which is how petich stores a saga payload;
- the price: a container per test class' lifetime, and a CI job that needs Docker. Both known, both
  cheap next to a green repository suite that is green about H2.

### D17. Turbine wherever a `Flow` is the subject

Decision: `app.cash.turbine` for the realtime source, the booblik consumer and any client view model.
Not for suspend functions that return a value — `runTest` alone is the tool there.

Why: the failures worth catching in these three are ordering, completion and *absence* — "no further
emission" is the assertion that catches a duplicate update, and it is the one that is unwritable
without Turbine and therefore unwritten.

### D18. Time is a dependency

Decision: a `Clock` is injected, never `Clock.System.now()` at a call site, in every place that
computes a deadline or an expiry.

Why: petich's `Suspend(ttl)` and its sweeper, a package's expiry, a counter's period and a tariff's
billing boundary are four separate clocks-in-disguise, and every one of them is untestable without
moving time. The alternative — a test that waits — is the reason suites get slow and then get skipped.

---

## 3. Risks and open questions

**Risk 7. The build compiles Android and iOS on every configuration.** One repository (D1) means a
server-only change still configures the client. Mitigation: convention plugins so configuration is
cheap and identical, and a measurement — if configuration time passes a minute, the split of D1 is
revisited with a number rather than a feeling.

**Risk 8. Formatting drifts on a plugin bump.** The ktlint CLI version is a bare string that nothing
resolves. Mitigation: the renovate comment of §1.1 and an explicit `version.set(...)`, so a bump is a
pull request with a diff rather than a surprise across every file.

**Risk 9. `singleOf` fails at runtime, not at compile.** Mitigation: a Koin `checkModules`-style test
that resolves the whole graph, run as an ordinary unit test — the graph is small and the failure it
catches is otherwise found by a user.

**Open question 4.** Netty or CIO for the server engine? The nearest prior art uses CIO with an import
alias (§1.4), and SSE (D7) is the load-bearing case. Hypothesis: CIO, because the connection profile is
many idle long-lived streams rather than high request throughput. Settled in `B-15` with the SSE
endpoint in front of it, and the alias gotcha applies from the first line either way.

**Open question 5.** Does konekt join the mutagen set so that JVM and server builds run on the Linux
box? It cannot join entirely: `iosSimulatorArm64Test`, `xcodebuild` and the simulator only exist on the
Mac, and the client is half of this repository. Hypothesis: yes for `:server:*` and the JVM test tasks,
Mac-local for everything Apple. Settled at `B-01`, and if the answer is yes the replica's `build/`
directory becomes a known hazard rather than a discovered one.

---

## 4. What happens next

`B-01` carries the whole of §1.1 and D11–D12; `B-02` carries D16 and the Flyway migrations petich does
not write. `B-31` through `B-34` carry `Money`, Testcontainers, the injected clock and the error
contract. Nothing else should start before `B-01`, because every one of the decisions above is
expressed in a build file.
