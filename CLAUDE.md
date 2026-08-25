# CLAUDE.md — konekt

A white-label subscriber account for an eSIM MVNO: Ktor on Kotlin/JVM, Compose Multiplatform on
Android and iOS, backend-driven UI. Built as a reference for six toolkits — kompot, petich, booblik,
katcher, metrik, tracy — with every external system (BSS/OCS, SM-DP+, payments, SMSC) mocked.

Gradle 9.7.1, Kotlin 2.4.10, Ktor 3.5.2 on the **CIO** engine, Koin 4.2.2, Exposed 1.4.0, Postgres. Java 25 is mandatory
rather than chosen: kompot and petich publish variants tagged `org.gradle.jvm.version = 25`, and
Gradle refuses to build a module on anything lower against them. The full version table, each row read
from a registry rather than recalled, is [docs/research/research-stack.md](docs/research/research-stack.md) §1.1.

## How to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was read
   in the toolkits and what follows from it. Skipping this file costs a day per finding, and five of
   its eleven facts contradict the original brief:
   - **`kompot-auth` is one action**, `update_session`, not a session system. OTP, tokens, refresh
     and logout are ours (§1.5);
   - **the wire has no vocabulary for shape.** `kompot-core` declares `ColorToken` and
     `TypographyToken` and nothing else; a brand's radii are a client build constant, deliberately
     (§1.2);
   - **`RemoteThemeDesignSystem` drops `resolveSurface`**, so a server theme silently reverts every
     surface customisation the moment it arrives. konekt inverts the wrapping to survive it (§1.3);
   - **petich drops outbox events silently** when handed a repository without outbox support — the
     saga still completes and every natural assertion still passes (§1.7);
   - **katcher publishes no Apple target**, so the iOS build reports no crashes at all (§1.9).
2. [docs/research/research-stack.md](docs/research/research-stack.md) — what is built on top of the
   toolkits: versions, the module layout, the layer rules, `Money`, the test harness. Four of its
   findings change how code is written rather than which library is used:
   - **`exposed-core` publishes no common metadata**, so any module touching Exposed is
     `kotlin("jvm")` and the data layer is the one layer that cannot be common code (§1.2);
   - **MockK publishes `common` and `jvm` and nothing else** — it resolves in `-server-domain`
     (`jvm()` only) and not in any module that also targets iOS or Android, where the double is a
     hand-written `object : XRepository { … }` (§1.3);
   - **repository and route tests run on a real Postgres**, not H2 and not a mock: the defect they
     exist for lives in the SQL, and H2's compatibility mode diverges on `ON CONFLICT`,
     `SELECT … FOR UPDATE` and `jsonb` — all three load-bearing here (D16);
   - **only the server formats money.** The server builds the screen, so the client renders a `text`
     and cannot format inconsistently (D15).
3. [backlog.md](backlog.md) — the goal, the stages and the index. Items are one file each in
   `docs/backlog/`; the index between the markers is generated, so edit the item and run
   `python3 scripts/backlog_index.py`.
4. The layer document the task belongs to — `docs/features/`, `docs/screens/`, `docs/api/`,
   `docs/services/`. **These four are still empty**, and that is now a gap rather than a stage: they
   were empty because there was no code, and there are four feature verticals now. `B-39` is the item.
   Until it closes, the backlog item and the research documents are the whole written record, and the
   auth tier of a route is readable only from `Application.kt`.

For anything touching the interface, [docs/design/design-app-canvas.md](docs/design/design-app-canvas.md)
carries the component dictionary and the three frames that describe states a naive implementation
cannot reach.

## Where things build

This repository is a mutagen session (one-way replica, alpha here, beta `konekt` on the WSL box).
**The server and the JVM tests build there**, through the wrapper:

```bash
~/.claude/bin/wsl-run ./gradlew build
```

**What that green result covers, measured rather than assumed** ([research-stack](docs/research/research-stack.md) §1.7):

| On the Linux box | |
|---|---|
| `compileKotlinIosArm64` / `IosX64` / `IosSimulatorArm64` | **run** — Kotlin/Native cross-compiles the Apple klibs |
| `linkDebugTestIos*`, `iosX64Test`, `iosSimulatorArm64Test` | **SKIPPED**, inside `BUILD SUCCESSFUL` |

So a green build means the Apple code *compiles*. It says nothing about any Apple test: Gradle warns
(`w: ⚠️ Native task 'iosSimulatorArm64Test' is disabled`), but the build succeeds and the standard
advice attached to that warning is a setting that silences it. The iOS test run is a separate named
command, on the Mac:

```bash
LOCAL=1 ./gradlew :shared:components:iosSimulatorArm64Test
```

It works as of 2026-08-25: iOS 27.0 runtime plus a simulator device. **Both are needed** — with the
runtime installed and no device created, the task fails with "Check that requested SDK is installed",
which blames the SDK that is installed. `xcrun simctl list devices available` is what tells the two
apart. CI still has no Mac runner with a runtime; that is `B-37`.

**Everything Apple stays on the Mac** — `iosSimulatorArm64Test`, `xcodebuild`, the simulator, the
screenshot tasks (`LOCAL=1 ./gradlew …` gets past the WSL hook). So does **`generateMigrations`**, and so does every other task that writes files:
one-way replication reverts anything a tool writes on the replica, so a run there looks like it did
nothing. Edits and `git` happen on the Mac too; the replica has its own `HEAD` and a diff taken there
proves nothing.

`build/` is in the ignore list because the replica deletes whatever the Mac does not have, and the
symptom of it not being ignored is a build error that reads like a compilation failure.

## How a feature is laid out

A feature is a vertical of modules, not a package — `feature/<name>-shared-api`,
`-server-domain`, `-server-data`, and a client module when there is one. The layering is then the
compiler's business: `-server-domain` cannot see Exposed, so it cannot accidentally depend on it,
which is the entire reason the repository interface exists. `feature/auth-*` is the worked example.

**A feature never depends on `:server`.** `:server` composes features. Anything more than one feature
needs lives in a shared module instead:

| Module | What is in it |
|---|---|
| `:shared:domain` | `Money`, `KonektException`, `suspendRunCatching`, `KonektClock` — KMP, no framework |
| `:shared:db` | the tables no feature owns (`subscriber`, `account`), the migrations, `DatabaseFactory`, and the Postgres test harness as **test fixtures** |
| `:shared:server-common` | the principal, `ownedOr404`, the `StatusPages` mapping, `MoneyFormat`, the petich clock adapter |
| `:shared:components` | the nine wire types of the component dictionary |

Everything in `:shared:server-common` got there the same way: a feature needed it and could not see
`:server`. That has happened four times now, so it is the rule rather than four accidents — anything a
second feature would want goes there first, not into `:server`. Not into `:shared:domain` either: the
client depends on that, and `MoneyFormat` is in `server-common` precisely because a client must not
reach it.

**Two Gradle projects may not share a simple name.** `:shared:server` beside `:server` produced a
circular dependency inside `:server` naming neither module.

## Rules that are cheap to follow and expensive to discover

- **Never `call.respond` a `KompotComponent`.** It drops the `"type"` discriminator on the root of the
  tree; nested children serialise perfectly, which is what makes it easy to miss, and the client then
  receives an unknown component for the whole screen and draws nothing. Use
  `call.respondKompotComponent`.
- **The client is JVM-only, and that is upstream rather than a choice.** kompot's Compose half —
  `kompot-client`, `kompot-theme-client`, `kompot-ds-material-compose`, `kompot-forms-client`,
  `kompot-wizard-client`, `kompot-images-client-coil` — publishes `-android`, `-desktop`,
  `-wasm-js` and **no iOS artefact**, while the protocol half publishes the three iOS targets. So
  `:client` does not use `konekt.multiplatform`. youndie/kompot#84. And when it closes the answer is
  two iOS targets, not three: Compose stopped publishing `iosX64` after `1.11.0-alpha01`.
- **Compose versions are matched to the toolkit's binaries, not to the newest release.** The client
  pins `1.11.1` with material3 `1.11.0-alpha07`, named by coordinate rather than through the
  plugin's `compose.*` accessors. A newer foundation beside the toolkit's material3 resolves and
  compiles and then throws `AbstractMethodError` inside a renderer.
- **Never name a kompot version.** One `platform("io.github.youndie:kompot-bom")` and no version on
  any kompot coordinate. The tail digit of a version is the CI run number, so two coordinates one run
  apart resolve into a combination nobody ever built.
- **katcher is three version lines**, not one: server, `client`, and `client-android` plus the Gradle
  plugin. They are separate entries in the catalogue and the catalogue says why.
- **A session is a family, not a token.** The access token carries its family id and the
  authentication provider refuses a revoked one, so logout and a detected theft take effect at once —
  at the cost of one indexed read per authenticated request. A refresh token exchanged twice ends the
  family; the arbitration is a conditional `UPDATE`, never a read-then-write.
- **`authenticate { }` proves the caller is somebody, not that the thing is theirs.** The owner check
  lives in the use case beside the principal, through `ownedOr404` — which answers **404 and not
  403**, because a 403 confirms the resource exists and hands out an enumeration oracle.
- **An issue outside `youndie/*` is asked about first.** Our own repositories are the working
  arrangement and need no permission; anybody else's tracker costs them time and cannot be quietly
  withdrawn. Write the finding into
  [research-upstream-proposals](docs/research/research-upstream-proposals.md) and ask.
- **Do not fork a toolkit.** A gap goes upstream as an issue
  ([docs/research/research-upstream-proposals.md](docs/research/research-upstream-proposals.md)),
  konekt works around it locally, and the workaround carries a comment naming the issue — so that the
  next person deletes it instead of inheriting it.
- **No endpoint path exists as a string.** `@Resource` classes live in `<feature>-shared-api` and are
  the only place a path is written; `ktor-server-resources` on the server, `ktor-client-resources` in
  the client. The check before a pull request is `grep` for `/api/` outside `*-shared-api` — it must
  return nothing.
- **`singleOf(::XImpl)` resolves defaulted constructor parameters through the container.** The Kotlin
  default is ignored, and a parameter whose type has no binding throws `NoDefinitionFoundException` at
  runtime while the compiler says nothing. Such a repository is registered with an explicit lambda.
- **`suspendRunCatching`, never `runCatching`.** Plain `runCatching` swallows
  `CancellationException`; the symptom is a request that will not stop, and nobody attributes it to
  this. `RunCatchingUsageTest` refuses it in the sources outright.
- **Time is a `KonektClock`, injected.** `ClockUsageTest` refuses `Clock.System` anywhere but
  `KonektClock.kt`. petich's clock comes from the same one through `asPetichClock()`, so a test that
  moves time moves it for the saga sweeper too.
- **A live update names the node it replaces.** The component id in an `UpdateComponentMessage` must
  be the id the screen already has — derive it from the subject (`counter-data`), never generate one.
  A random id is a frame that arrives and changes nothing, silently.
- **`KompotUpdateBroadcaster` must be started**, or it refuses to broadcast and says why. The failure
  it prevents is a publish reaching a bus nobody collects from.
- **The broker's topics are fixed at startup and declared in two files** — `deploy/compose.yaml` and
  `EventTopics`. booblik creates nothing on demand, so an event routed to a topic that does not exist
  is a publish that fails forever and a stuck outbox. Two tests pair the halves.
- **The broker publishes no host port, and a test enforces it.** It has neither TLS nor
  authentication — both deliberately absent — so reachability is the whole of its security model.
- **A saga test uses `runBlocking`, never `runTest`.** `runTest`'s virtual clock skips time forward
  for a suspended coroutine, and the engine wraps every interceptor in `withTimeout` — so the first
  real database call inside a step jumps past the phase timeout, the step is cancelled and the saga
  compensates. petich swallows the cancellation into the compensation, so nothing is logged and what
  you see is a saga that rolled itself back for no reason.
- **Compensation only walks back through steps that actually ran.** An event announced from a step the
  saga never reached is an event that never fires for the case it exists for. Announce a reversal from
  the step whose work is being undone.
- **Inside `Table.insert { }` the table is the receiver**, so a bare name resolves to the COLUMN. A
  parameter wins that resolution and a class property does not — which is why it bites in a test seed
  and not in a repository, and why the fix is a differently named local.
- **A refusal is a `KonektException`**, and the `when` mapping it to a status has no `else` — add a
  case to the sealed hierarchy without mapping it and the build fails. A route answers
  `.getOrThrow()` and stops; `.onFailure` is for the one error that needs a body of its own.
- **Never wrap a `Result` in a `Result`.** A repository unwraps what it gets from below and throws a
  domain exception; a `failure` nested inside a `success` travels past the error handler in silence.
- **`io.ktor.server.cio.CIO` collides by name with the client's `CIO`.** Any file that also builds an
  `HttpClient` needs an import alias, or `embeddedServer` receives the client engine.
- **A migration is compatible with the code already running.** During a roll both versions talk to
  one schema. Expand then contract, one release apart — the table of changes is in
  [research-stack](docs/research/research-stack.md) D22. `generateMigrations` emits the destructive
  form because it is the shortest; its output is a draft.
- **A concurrent index needs two Flyway settings.** `V<n>__x.sql.conf` with
  `executeInTransaction=false`, *and* `flyway.postgresql.transactional.lock=false`. With only the
  first, Flyway's own lock deadlocks against the index build and the migration hangs — which during a
  deploy reads as a slow rollout.
- **A component is registered by KSP, so `build` proves nothing about the dictionary.**
  `:shared:components` switches off every per-target KSP task so generation happens once against the
  common metadata, and a disabled KSP task is the classic way to get a green and empty build. What
  proves it is `KonektRegistrationTest`, which round-trips **each** of the nine through
  `generatedKonektSerializersModule` and asserts it did not come back as an `UnknownComponent`.
  Adding a component means adding it to `konektWireNames` and to `konektDictionary` — the tests walk
  both and fail if they disagree.
- **The database is Postgres 18 in a Testcontainer, never H2 and never a mock.** `:server:test` needs
  Docker, so it runs on the Linux box like everything else. `PostgresHarness` starts one container
  for the whole JVM and `truncateAll()` runs between tests.
- **`KonektSchemaTest` is what proves the migrations are complete**, not review: it asks Exposed,
  after Flyway has run, whether any DDL is still required for petich's four tables and konekt's
  three, and asserts the answer is empty. It has no exemptions and should not grow one.
- **Money is `io.konekt.domain.Money`, and only `:server` can format it.** The product runs in
  `Currency.DEFAULT` (USD). A currency added to the enum needs a row in `MoneyFormat`'s layout table
  or the screen cannot be built.
- **Never write a migration by hand from the generator's output.** `scripts/generate-migration.sh`
  drafts one; the draft breaks a rolling deploy by construction, and it overwrites its own files —
  each is named from its first statement plus a version stamped to the second, so tables sharing a
  parent collide and one is lost silently (JetBrains/Exposed#2897). Rewrite it as an expand/contract
  pair, renumber it, and let the tests decide: `MigrationFilesTest` on the names, `KonektSchemaTest`
  on what they produce.
- **A BOM does not reach the KSP processor classpath.** That configuration needs its own
  `add("kspCommonMainMetadata", platform(libs.kompot.bom))`, or the coordinate resolves with no
  version and the error ends in a bare colon.
- **Inside a KMP source-set dependency block it is `project.dependencies.platform(...)`.** The
  receiver there is `KotlinDependencyHandler`, which has no `platform` of its own, and the error names
  the function rather than the receiver — so it reads as a missing import.
- **kompot generates component registrations and nothing generates action ones.** An action of ours
  goes into a hand-written `SerializersModule` and into the application's `Json`. Forgetting it
  compiles, starts and draws every screen: encoding is fine, and the failure is the decode on the way
  back in, on the one request the action exists for. Cover it by posting the server's own action back,
  as `EsimWizardRoutingTest` does.
- **`wizard-core` is the step machine and `kompot-wizard` is its form-shaped wire half.**
  `WizardScreenComponent` needs a `formId` naming a real `FormSchema`, so a flow with no form takes
  the engine only and draws its own chrome from `step_meter`. The engine also cannot refuse a
  transition — `Next` moves or stays — so every "not from here, not yet" rule runs in the use case
  BEFORE the transition, and answers with the same step plus a reason rather than with a status code.
  A refusal thrown as an exception is a wizard that is neither here nor there.
- **Anything that reads a generated directory must declare the dependency**, ktlint included —
  excluding generated files from the *check* does not remove the directory from the task's *inputs*.
- **A feature that is built is not a feature that runs.** The usage feature shipped complete and
  tested and was installed by nothing: five imports in `Application.kt` with no use beneath them, so
  a completed purchase granted no allowance and no route could read a counter. `KoinGraphTest`
  cannot see this — it verifies the modules it is GIVEN. `FeatureModulesReachTheGraphTest` reads the
  composition root as text instead. A worker nobody starts is the same defect and is still open
  (`B-16`).
- **Never `call.respond` a component tree, and there is now a test that says so.**
  `CallRespondUsageTest` reads the sources, because `call.respond(anything)` compiles. It was proved
  to bite by rewriting a real route.
- **A green check that visited nothing is the failure mode here**, twice over: the conformance kit
  passes silently when it finds no targets, and petich completes sagas silently when it is dropping
  their events. Both have their own backlog item and both assert on coverage rather than on a verdict.

## Documentation

The format is [docs-bootstrap](https://github.com/youndie/docs-bootstrap). Documents in English, code
in English.

```bash
pip install pyyaml
make check
```

`make check` is the gate and CI runs exactly it. `make report` is the two non-blocking reports; while
there is no code, `code_anchors` reports every anchor as missing, which is correct.
