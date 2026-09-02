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

### 1.5 The Exposed Gradle plugin generates migrations from a diff, and a diff is destructive by default

Verified in the plugin's published coordinates and in JetBrains' own documentation for it.

| Fact | Where verified |
|---|---|
| the plugin id is `org.jetbrains.exposed.plugin.gradle`, at `1.4.0` — the same line as Exposed itself | `org/jetbrains/exposed/plugin/org.jetbrains.exposed.plugin.gradle.plugin/maven-metadata.xml` |
| it is published to **Maven Central, not the Gradle Plugin Portal** | absent from `plugins.gradle.org/m2/…`; present in `repo1.maven.org` |
| its task is `generateMigrations`: it compares `Table` definitions against a live schema and writes the SQL difference | jetbrains.com/help/exposed/exposed-gradle-plugin.html |
| it is configured by an `exposed.migrations` block needing `tablesPackage` plus either a database URL or `testContainersImageName` | same |
| given Testcontainers it applies the existing Flyway migrations first, then diffs against that | same |
| it **does not apply** anything — applying stays Flyway's job | same |
| the Flyway Gradle plugin `org.flywaydb.flyway` is at `13.3.0`, the same version as `flyway-core` | `plugins.gradle.org/m2/org/flywaydb/flyway/…` |
| Exposed also publishes `exposed-money` at `1.4.0` | `org/jetbrains/exposed/exposed-money/maven-metadata.xml` |

**Consequence 1.** The source of truth moves: the `Table` objects describe the schema, and the Flyway
files under `db/migration` become **generated drafts** that are read, edited and committed — not
hand-authored and not applied unread. `settings.gradle.kts` needs `mavenCentral()` inside
`pluginManagement.repositories`, which is not the default and whose absence reads as "plugin not
found".

**Consequence 2, and it is the important one.** A schema differ emits the shortest SQL that makes the
two schemas equal: `ALTER TABLE … DROP COLUMN`, `RENAME`, `ALTER … TYPE`. Those are precisely the
statements that break a rolling deploy, because during a roll the old code is still reading the
column the diff just dropped. So `generateMigrations` produces a **draft**, and turning it into an
expand/contract pair is a manual step with a rule behind it (D22). The two things asked for in the
same breath — a generator and no downtime — pull against each other, and this is where the pull is
resolved.

**Consequence 3.** `tablesPackage` is a single package root, so every `Table` has to live under one.
With a per-feature module split the tables are scattered across `feature/*-server-data`, which is
fine as long as they share `io.konekt` as a root — and whether the plugin scans recursively is
checked at `B-02` rather than assumed.

**Consequence 4.** `exposed-money` is **not** taken. It is a column type over JSR-354 `javax.money`,
which is JVM-only, and `Money` has to live in the shared domain and cross the wire (D15). The column
mapping is two columns — a `BIGINT` of minor units and a `CHAR(3)` code — written once.

### 1.6 A concurrent index needs two Flyway settings, and getting one of them wrong is a hang

`CREATE INDEX CONCURRENTLY` is how an index is added to a live table without blocking writes, and it
cannot run inside a transaction block.

| Fact | Where verified |
|---|---|
| a migration opts out of the transaction through a sidecar config file `V<n>__<desc>.sql.conf` containing `executeInTransaction=false` | Flyway configuration documentation |
| that alone is not enough on PostgreSQL: Flyway's own lock is transactional, and it deadlocks against the concurrent index build | flyway/flyway issues [#3840](https://github.com/flyway/flyway/issues/3840), [#3854](https://github.com/flyway/flyway/issues/3854) |
| the second setting is session-level locking — `flyway.postgresql.transactional.lock=false` | same |

**Consequence.** The failure mode of getting this wrong is not an error but a **hang**: the migration
never returns. In a deployment that runs migrations before the application starts, a hang is
indistinguishable from a slow rollout, and the instinct is to wait longer. So the migration step gets
a deadline of its own, shorter than the deploy's, and the deadline exists to convert the hang into a
failure that names itself.


### 1.7 Nothing runs the iOS tests today, and both halves of that are silent

Measured on this repository's own skeleton, with a throwaway source file in `shared/domain` so the
tasks had something to do.

| Fact | Where verified |
|---|---|
| on the Linux box `compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64` and `compileKotlinIosX64` **run** — Kotlin/Native cross-compiles the Apple klibs there | `wsl-run ./gradlew :shared:domain:build` |
| on the same run `linkDebugTestIosX64`, `iosX64Test`, `linkDebugTestIosSimulatorArm64` and `iosSimulatorArm64Test` report **SKIPPED**, and the build is `BUILD SUCCESSFUL` | same |
| on the Mac `iosSimulatorArm64Test` fails with *"Xcode does not support simulator tests for ios_simulator_arm64. Check that requested SDK is installed."* | `LOCAL=1 ./gradlew :shared:domain:iosSimulatorArm64Test` |
| `xcrun simctl list runtimes` prints an empty list; `xcode-select -p` points at `Xcode-beta.app` | the Mac |

**Consequence 1.** A green `wsl-run ./gradlew build` means *the Apple code compiles*. It does not mean
any Apple test passed, because the test tasks are skipped rather than absent, and a skipped task is
indistinguishable from a passing one in the summary line. The iOS test run is therefore a **separate,
named command**, not something anybody may assume `build` covered.

*Corrected while doing `B-03`*: the build is not entirely silent about it — Gradle emits
`w: ⚠️ Native task 'iosSimulatorArm64Test' is disabled … simulator tests require macOS` for each one.
That is better than nothing and is still not a gate: it is a warning in a build that succeeds, it
appears once per target per module, and the standard advice attached to it is a property that turns
it off. The conclusion is unchanged; the earlier wording here overstated it.

**Consequence 2.** Right now that separate command fails on the missing simulator runtime, so **no
iOS test in this repository is executed by anything** — not by CI, not locally. The code is compiled
and unrun. The fix is a several-gigabyte `xcodebuild -downloadPlatform iOS`, which is the machine
owner's call rather than a build step, and until it happens the gap is `B-37` rather than an
assumption.

This is worth separating from [research-architecture](research-architecture.md) §1.9, which says the
iOS build reports no *crashes*. That is about production. This is about the tests, and the two gaps
compound: an iOS defect is neither caught before release nor reported after it.


### 1.8 Three things the build teaches only by failing, found while building the dictionary

Measured on `:shared:components`, the first module in this repository with KSP and more than one
target.

| Fact | How it showed up |
|---|---|
| a BOM constrains only the configuration it is declared in, and the KSP processor classpath is its own configuration | `Could not find io.github.youndie:kompot-registry-processor:` — trailing colon, nothing after it |
| excluding generated files from ktlint's **check** does not remove the generated directory from the task's **inputs** | Gradle's undeclared-dependency validation failed `runKtlintCheckOverCommonMainSourceSet` against `kspCommonMainKotlinMetadata` |
| `platform(...)` does not exist on the receiver of a KMP source-set `dependencies { }` block | `Unresolved reference 'platform'`, which names the function rather than the receiver |
| an installed simulator **runtime** with no simulator **device** still reads as a missing SDK | `Xcode does not support simulator tests for ios_simulator_arm64. Check that requested SDK is installed.` after `xcodebuild -downloadPlatform iOS` had succeeded |
| a backtick test name containing a **comma** compiles on the JVM and fails on Kotlin/Native | `Name contains illegal characters: ","` from `compileTestKotlinIosX64`, on a suite that was green on `jvmTest` |

**Consequence 1.** `add("kspCommonMainMetadata", platform(libs.kompot.bom))` goes beside the processor
coordinate. Without it the only alternative is writing a version literal for one coordinate, which is
the single thing `B-01`'s rule exists to prevent — so the rule survives, at the cost of one line that
looks redundant and is not.

**Consequence 2.** The `dependsOn("kspCommonMainKotlinMetadata")` matcher covers ktlint tasks as well
as compile and sources-jar tasks. The general shape: **anything that reads a generated directory has
to declare the dependency, whether or not it acts on what it reads.**

**Consequence 3.** Inside a source-set block the form is `project.dependencies.platform(...)`.

**Consequence 4.** `xcodebuild -downloadPlatform iOS` is necessary and not sufficient. A device has to
exist too — `xcrun simctl create <name> <devicetype> <runtime>` — and until one does, the error blames
the SDK, which is installed. The two states are indistinguishable from the message, and only
`xcrun simctl list devices available` separates them.

**Consequence 5.** Kotlin/Native's identifier rules are stricter than the JVM's even inside backticks,
and a comma is one of the characters it refuses. So a `commonTest` suite can be entirely green on
`jvmTest` and not compile for iOS at all — which is the same shape as §1.7 seen from the other side,
and the practical reason the iOS run is a step of its own rather than something `build` is trusted to
have covered.


### 1.9 The migration generator omits a table and says nothing, and it cannot run where its output is wanted

Measured on this repository's own schema while doing `B-02`, with the Exposed Gradle plugin `1.4.0`
against Postgres 18 through `testContainersImageName`.

| Fact | How it showed up |
|---|---|
| the plugin id is `org.jetbrains.exposed.plugin`, **not** `…plugin.gradle` | `Plugin … was not found`; the marker artefact is `org.jetbrains.exposed.plugin:org.jetbrains.exposed.plugin.gradle.plugin` |
| the output directory property is `fileDirectory`, and `fileExtension` needs the dot — `"sql"` yields `…SUBSCRIBERsql` | `Unresolved reference 'migrationsDir'`, then a file Flyway would not pick up |
| **the generator overwrites its own output**, losing a table, with exit code 0 | three tables in, two out — see the mechanism below |
| versioned filenames are stamped to the **second**, so one run writes several files with the same version | `V20260825104001__CREATE_TABLE_PROBE.sql` beside `V20260825104001__CREATE_TABLE_SUBSCRIBER.sql`; Flyway then answers `Found more than one migration with version …` (verified against 13.3.0) |
| the plugin's own Flyway step did not find `db/migration` | `No migrations found. Are your locations set up correctly?` — so it diffed against an empty database and drafted a schema that already exists |

**The mechanism, and a correction to how this was first written down.** The first version of this
section — and of the upstream report — said "a table is omitted when two tables reference the same
parent". That is the trigger, not the cause, and it was an inference from black-box behaviour in this
project rather than something isolated. Isolating it changed the answer:

- `MigrationUtils.statementsRequiredForDatabaseMigration(parent, childOne, childTwo)` against the same
  empty database returns **all three** `CREATE TABLE` statements, in dependency order, in any
  argument order. The diffing is correct and was never at fault;
- the plugin writes **one file per table, each containing that table's whole dependency closure**, and
  takes the file's description from its **first statement** — which for a child is the parent. So
  every child of one parent yields a file described `CREATE_TABLE_<PARENT>`;
- with the default second-resolution version, version *and* description are then identical, the files
  resolve to one name, and they overwrite each other. What survives is the last one written.

Changing nothing but `fileVersionFormat` to `MAJOR_MINOR` shows all three files and makes it plain.
Three tables with no foreign keys between them lose nothing — their descriptions differ — but still
share one version, which is the same flaw in a case where Flyway catches it instead.

Reported as [JetBrains/Exposed#2897](https://github.com/JetBrains/Exposed/issues/2897), rewritten
after this check; fixed by [#2898](https://github.com/JetBrains/Exposed/pull/2898) and released in
Exposed `1.5.0` on 2026-08-26, which this build takes since 2026-09-02. The version collision within
one run stays — the generator still stamps to the second — so a draft is still renumbered by hand.

*Amended 2026-08-25.* The index half of this — petich declaring none of the indexes its comments
asked for — closed in petich `0.1.0.8`, so `KonektSchemaTest` no longer exempts `DROP INDEX` and
asserts strict equality. Retiring an exemption is worth as much attention as adding one: while it
stood, an index that genuinely should have been dropped was invisible to the same check.

**The lesson is not about Exposed.** A reproducible symptom was published as a mechanism, in a public
report, where it would have sent a maintainer to the wrong part of the code. The distance between
"three tables in, two out" and "the diffing drops a table" is one experiment — running `MigrationUtils`
directly — and it was not done before writing. Anything stated as a cause has to be separated from
the observation it was inferred from, and the check is cheap exactly when it feels unnecessary.

**Consequence 1.** The draft is a draft in a second sense. D22 already said the generator's *form* is
unsafe — the shortest SQL that equalises two schemas is `DROP COLUMN` and `RENAME`, which is what
breaks a rolling deploy. Now its *contents* are unreliable too, so the review has to check
completeness as well as safety, which is the part a reviewer is worst at. What actually catches it is
a machine: `KonektSchemaTest` asks Exposed, after Flyway has run, whether any DDL is still required.
That check found the missing table; nothing else did or could.

**Consequence 2 — a correction to D23.** That decision said `generateMigrations` is a Mac-local task,
because a file written on the mutagen replica is reverted. That is true and it is not the whole
constraint: the task needs **Docker**, and there is no Docker daemon on the Mac — only the client
binary. So the task cannot run on the Mac at all, and cannot usefully run on the Linux box either.
The resolution is `scripts/generate-migration.sh`: run it there, read the files back here. The
general shape is worth keeping — *two constraints that are each satisfiable can be jointly
unsatisfiable*, and the discovery cost was one failed run each way.


---

### 1.10 The client's Compose version is not a free choice, and the wrong one fails at render time

| Fact | Where verified |
|---|---|
| the toolkit's Compose modules are built against Compose Multiplatform `1.11.1` and material3 `1.11.0-alpha07` | `kompot-forms-client-desktop-0.31.0.74.pom`, and `kompot/gradle/libs.versions.toml` |
| `org.jetbrains.compose:compose-gradle-plugin` released `1.12.0`, so a fresh module picks it up by default | `repo1.maven.org` maven-metadata, 2026-08-25 |
| `org.jetbrains.compose.material3:material3` has **no stable release in either line** — `<latest>1.12.0-alpha03` | same |
| with the plugin at `1.12.0`, `foundation` and `runtime` resolve to `1.12.0` while material3 resolves to the toolkit's `1.11.0-alpha07` | `:client:dependencies --configuration jvmTestRuntimeClasspath` |

**Consequence.** Nothing fails to resolve and nothing fails to compile. The first screen containing a
text field dies at render time:

```
java.lang.AbstractMethodError: Receiver class androidx.compose.material3.OutlinedTextFieldDefaults$$Lambda
  does not define or inherit an implementation of the resolved method
  'abstract void applyStyle(androidx.compose.foundation.style.CustomStyleScope)'
  of interface androidx.compose.foundation.style.CustomStyle
```

That is a mixed pair rather than a bug in either half: material3 moves on its own version line, and
the plugin's `compose.material3` accessor is pinned to a version that loses the resolution to
whatever the toolkit asks for. So `:client` pins **the versions the toolkit's binaries were compiled
against** — `composeMultiplatform = "1.11.1"`, `composeMaterial3 = "1.11.0-alpha07"` — and names
material3 by coordinate rather than through the accessor. Newest is the wrong default here; matched
is the requirement. Raised upstream as the second half of
[youndie/kompot#84](https://github.com/youndie/kompot/issues/84), since nothing in the toolkit says
which versions it was built against.

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

### D19. The server engine is CIO

Decision: `io.ktor.server.cio.CIO`, closing open question 4.

Why: the load-bearing case is SSE (D7) — many long-lived, mostly idle streams rather than high
request throughput, which is the profile CIO's coroutine-per-connection model is shaped for, and the
profile Netty's thread pool is not shaped for. The price is that CIO is the less-trodden of the two on
the JVM; the mitigation is that the realtime endpoint has its own test (`B-15`) rather than being
covered incidentally.

**The import alias is not optional** wherever a file also constructs an `HttpClient`:
`io.ktor.server.cio.CIO` and `io.ktor.client.engine.cio.CIO` are the same simple name, and without an
alias `embeddedServer` receives the client engine (§1.4).

### D20. The build runs on the Linux box; everything Apple stays on the Mac

Decision, closing open question 5: this repository is a mutagen session — one-way replica, alpha
`~/Documents/GitHub/konekt`, beta `konekt` on the WSL machine, ignoring `build`, `.gradle`, `.kotlin`,
`.idea`, `.DS_Store` and VCS. `:server:*`, the JVM test tasks and anything Docker run there through
the `wsl-run` wrapper. `iosSimulatorArm64Test`, `xcodebuild`, the simulator and any screenshot run
stay local.

Why: the Mac has 16 GB and 41 GB of disk against the Linux box's 20 cores and 23 GB, and this build
compiles a server, an Android client and three iOS targets. The price is two hazards that are
properties of one-way replication and are named here so they are not rediscovered:

- **the replica's `build/` is deleted whenever it does not exist on the Mac.** It is in the ignore
  list for that reason, and the symptom when it is not is a build error that reads like a
  compilation failure;
- **anything a tool writes on the replica is reverted.** A formatter, a code generator or a
  `generateMigrations` run must happen on the Mac, or its output is rolled back on the next sync and
  the run looks like it did nothing. This one directly constrains D23.

`git` operations happen on the Mac. The replica has its own `HEAD` and a diff taken there proves
nothing about what will be committed.

### D21. End-to-end is a docker-compose stand, run by one command in both places

Decision: `deploy/compose.yaml` brings up Postgres, the booblik broker with its three topics, the
server, and the three observability binaries. The end-to-end suite drives it over HTTP and is the
same command locally and in CI.

Why:

- the scenario worth proving is the one that crosses every process — a purchase goes through a form,
  a saga, the outbox, the broker and back to an open SSE stream — and it is exactly the scenario no
  single-process test can reach. Every component test in this build can pass while that chain is
  broken at a seam;
- a stand that only CI knows how to start is a stand nobody debugs. One command, one file.

Shape, taken from the nearest working example of a compose stand on this stack:

- **`depends_on` uses `condition: service_healthy`, and the healthcheck asks a question the process
  must answer.** A TCP check passes on a hung process, because the kernel accepts a connection into
  the backlog with no help from it;
- topics are declared to the broker as configuration at startup — `BOOBLIK_TOPICS:
  orders:1,usage:1,notifications:1` — because booblik fixes its topic set then and never again
  ([research-architecture](research-architecture.md) §1.8). One partition each: ordering inside a
  topic is worth more here than parallelism nobody measures;
- host ports are overridable through environment variables. 8080 is the most contested port there is,
  and the stand should not be the thing that refuses to start;
- optional pieces sit behind compose profiles, so the default `up` is the shortest path to the
  scenario.

The price: end-to-end is the slowest and most fragile layer of any suite, so it stays small on
purpose — the sagas' happy path, the compensated path, and the live update. Everything else is proved
lower down.

### D22. No downtime means expand/contract, and the generated migration is a draft

Decision: a release never changes a column in place. Every schema change is a pair of releases —
**expand**, then **contract** — and each individual migration is compatible with the code that is
already running when it lands.

| Change | Release N | Release N+1 |
|---|---|---|
| add a field | add the column nullable, write it, keep reading the old source | make it `NOT NULL`, stop reading the old source |
| rename a field | add the new column, dual-write, backfill | read the new one only, then drop the old |
| drop a field | stop writing it | drop the column |
| change a type | add the new column, dual-write, backfill | switch reads, drop the old |
| add an index | `CREATE INDEX CONCURRENTLY` with both settings of §1.6 | — |
| add `NOT NULL` | add a `CHECK … NOT VALID`, then `VALIDATE CONSTRAINT` | `SET NOT NULL`, which the validated check makes cheap |

Why: during a rolling deploy both versions of the code run against one schema, and there is no moment
at which only one of them does. A migration that assumes otherwise works in staging, where one pod is
replaced instantly, and fails in production, where two are not.

- `generateMigrations` (§1.5) emits the destructive form, because the shortest SQL that makes two
  schemas equal is not the safe one. Its output is a draft; splitting it into the pair above is the
  reviewer's job and it is the reason the generated file is committed rather than applied.
- Every DDL statement carries a `lock_timeout`. An `ALTER TABLE` that waits for a lock queues every
  reader behind it, and a migration that blocks the table is downtime whatever the deploy does.
- The migration step runs **separately from and before** the application starts — the same image with
  a migrate-only switch, so the schema is already current when the first new process comes up and
  no two processes race to migrate. It gets a deadline shorter than the deploy's, per §1.6.
- The rule is checked, not trusted: the end-to-end stand of D21 runs the **previous** release's server
  image against the **new** schema, which is the state a rolling deploy actually passes through.

Not covered: data migrations large enough to need batching. When one appears it is a background job
with a resumable cursor, not a Flyway script, and that decision is taken then.

### D23. Flyway applies, the Exposed plugin drafts, and the plugin runs on the Mac

Decision: the Flyway Gradle plugin (`org.flywaydb.flyway:13.3.0`) plus `flyway-database-postgresql`
for applying, and the Exposed plugin (`org.jetbrains.exposed.plugin.gradle:1.4.0`) for drafting.
`mavenCentral()` goes into `pluginManagement.repositories` because the Exposed plugin is not on the
Plugin Portal (§1.5).

`generateMigrations` is a **Mac-local task**, for the reason in D20: it writes files, and files
written on the replica are reverted on the next sync. Its Testcontainers mode is what makes it usable
at all — it applies the committed migrations to a throwaway Postgres and diffs against that, so the
draft accounts for everything already in `db/migration` instead of against whatever a developer's
local database happens to hold.

**Corrected while doing `B-02` (§1.9).** Both halves of that paragraph were wrong in the same
direction. The task cannot run on the Mac, because it needs a Docker daemon and the Mac has only the
client binary; and its Testcontainers mode did **not** apply the committed migrations — it logged
`No migrations found. Are your locations set up correctly?` and diffed against an empty database. So
it runs on the Linux box through `scripts/generate-migration.sh`, which copies the drafts back, and
the draft is checked against reality by `KonektSchemaTest` rather than trusted.


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

**Risk 10. The migration generator writes the unsafe migration.** A schema differ emits
`DROP COLUMN` and `RENAME` because they are the shortest way to make two schemas equal, and both break
a rolling deploy (§1.5). Mitigation: the generated file is a draft that is edited into the
expand/contract pair of D22 before it is committed, and the check is the previous-release-against-new-schema
run in the end-to-end stand — a rule enforced only by review is a rule that holds until the week
somebody is in a hurry.

**Risk 11. A concurrent index migration hangs rather than failing** (§1.6), and a hang during a deploy
reads as a slow rollout. Mitigation: the migration step's own deadline, shorter than the deploy's, so
the hang becomes a failure that names itself.

**Open question 4 — settled.** The server engine is CIO; see D19.

**Open question 5 — settled.** This repository is a mutagen session; the server and the JVM tests
build on the Linux box, everything Apple stays on the Mac. See D20.

---

## 4. What happens next

`B-01` carries the whole of §1.1 and D11–D12; `B-02` carries D16 and the Flyway migrations petich does
not write. `B-31` through `B-34` carry `Money`, Testcontainers, the injected clock and the error
contract. Nothing else should start before `B-01`, because every one of the decisions above is
expressed in a build file.
