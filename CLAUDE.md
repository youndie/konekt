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
   - **katcher published no Apple target** until `client:0.6.2`, so the iOS build reported no crashes
     at all. Closed by youndie/katcher#25 and wired in `B-27`; tracy had the same gap and it is
     closed too (youndie/tracy#16, released in `0.1.13`). Kept in the list because the SHAPE recurs
     rather than because either is open: a toolkit publishing every target but the one a phone
     needs is a silence the agent cannot report, and it has now happened twice (§1.9).
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
4. The layer document the task belongs to — [`docs/features/`](docs/features/),
   [`docs/screens/`](docs/screens/), [`docs/api/`](docs/api/),
   [`docs/services/`](docs/services/). All four are filled in as of `B-39`, from the code rather than
   from the backlog: four features with BDD scenarios, four screens, five endpoint documents carrying
   **the auth tier of every route the server installs**, and three services. The map is
   [docs/README.md](docs/README.md).

   Two things to know before trusting a line in them. **They describe what exists** — anything not
   built stays in its backlog item — and **what was read out of the code is separated from what was
   not**: a document that cannot establish something says it does not cover it. If you find one that
   blurs the two, that is a defect worth an item, because both halves then look equally
   authoritative. The endpoint documents are also where a route's quirks live: which refusals are
   status codes and which are screens, and which paths are spelled twice.

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
- **The client builds for iOS, and still does not use `konekt.multiplatform`.** kompot's Compose half
  published no iOS artefact at all until `0.31.0.76` (youndie/kompot#84) — that is closed, measured at
  `0.32.0.77`: `kompot-client`, `kompot-theme-client` and `kompot-ds-material-compose` each declare
  `ios_arm64` and `ios_simulator_arm64`. The reason the convention plugin still does not fit is the
  second one, which outlived the first: it declares all THREE iOS targets and the Compose half has
  two, because Compose stopped publishing `iosX64` after `1.11.0-alpha01`. So `:client` names its own.
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
  the client. The check before a pull request is `grep` for `/api/` outside `*-shared-api`. It does
  **not** return nothing today, and the two reasons are worth telling apart: test sources spell paths
  on purpose, and `HistoryScreen.pageUrl` spells `/api/v1/screens/history/page` in production code
  beside the `@Resource` that already declares it — because `LoadPageAction` takes a URL string. That
  is one path with two spellings and nothing holding them together; it is written down in
  [endpoint-purchase](docs/api/endpoint-purchase.md) rather than quietly tolerated. Anything **new**
  outside a `*-shared-api` is a defect.
  The one honest exception is `/api/v1/realtime`: both halves of SSE take a plain string and
  `ktor-client-resources` has no SSE builder, so the string lives once, in `feature/realtime-shared-api`.
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
  **It does not actually replace the node, and the difference is a defect rather than pedantry.**
  kompot collects updates into a `Map<String, KompotComponent>` behind `LocalKompotRealtimeUpdates`,
  and `KompotRegistry.RenderNode` draws `updates[node.id] ?: node` — an overlay above whatever tree is
  being drawn, cached or freshly fetched, chosen per node at render time. **Nothing ever removes an
  entry.** So a component recorded before a stream gap goes on shadowing the correct component of a
  screen fetched after it, for the life of the composition, with a healthy network and no error
  anywhere. Read out of `0.31.0.74` and written up in
  [B-18](docs/backlog/B-18-cache-versus-realtime.md); the decision is that konekt owns the map and
  empties it on `streamRestarted`.
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
  form because it is the shortest; its output is a draft. **`ExpandAndContractTest` enforces this**:
  anything that takes something away needs a `-- contract: expanded in V<n>` line, and the named
  expand must exist and come earlier. The marker is a person asserting what the gate cannot check —
  that nothing running still reads it — at the moment they are best placed to know.
- **A concurrent index needs two Flyway settings.** `V<n>__x.sql.conf` with
  `executeInTransaction=false`, *and* `flyway.postgresql.transactional.lock=false` — the second is set
  once in `DatabaseFactory`, because it is a property of how Flyway takes its lock rather than of any
  one script. With only the first, Flyway's own lock deadlocks against the index build and the
  migration hangs, which during a deploy reads as a slow rollout. `ConcurrentIndexTest` runs the
  recipe against a real Postgres with a live writer, and measures the plain variant in the same run
  as its control: a threshold in milliseconds would measure the runner.
- **A migration that has run anywhere is immutable, comments included.** Flyway checksums the whole
  file, so correcting a comment in a deployed migration stops the next release: the `migrate` init
  container refuses on validation, the deployment never becomes available, and helm rolls back on its
  timeout with a message about readiness — nothing in that chain names the edit. It happened, to
  exactly one comment (`B-65`). `applied-migrations.checksums` now locks a number per file and
  `AppliedMigrationsAreImmutableTest` fails on a laptop instead; `MigrationChecksumOracleTest` keeps
  the lock honest by comparing it to what real Flyway writes into `flyway_schema_history`, because a
  guard that checks its own arithmetic agrees with itself whatever the toolkit does. Note that the
  stand cannot catch this class at all: it migrates from empty, so no checksum is ever compared.
- **Every migration sets `SET lock_timeout`.** A statement waiting for a lock queues every later
  reader behind it, and a blocked table is downtime whatever the deploy is doing.
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
- **A number crossing a boundary carries its unit in its type.** The top-up form and the DTO endpoint
  met at one `Long` called `amountMinor`, and the form was handing it whole units: typing 5000
  credited $50, and typing 50 was refused by the screen that had just named $10 as the minimum
  (`B-67`). `TopUpAmount.Whole`/`Minor` now says which, and the conversion happens inside the use
  case where the currency — and therefore the exponent — is known, never at the edge. The same rule
  bans a hand-written `* 100` anywhere: `Money.ofMajor` exists because a hundred is right for the
  dollar and wrong for the dinar. Note what stayed green throughout: every test below the wire calls
  the use case and hands it minor units, which is the correct unit AT THAT BOUNDARY. The boundary
  nothing crossed was the one a person stands on, which is why the guard is a stand scenario that
  reads the minimum off the served screen and types it.
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
- **CI is three jobs: the documentation gate, the build, and the stand.** The build asks for Docker before Gradle
  does — half the suite is Testcontainers, and a runner without a daemon otherwise fails inside
  Testcontainers with a message about a socket. `ubuntu-latest` because the repository is public;
  a private one would need a self-hosted label, which is a bill rather than a setting.
- **No CI job may name an Apple test task.** On Linux those tasks are SKIPPED inside a green
  `BUILD SUCCESSFUL`, so a job that named one would go green forever having tested nothing.
  `AppleTestsAreNotClaimedTest` refuses it. Note that a guard reading a file outside the module is
  not a Gradle input: changing only that file leaves `:server:test` UP-TO-DATE, and locally it needs
  `--rerun-tasks` to be believed.
- **An unknown component draws a block, and the block does not name the type.** konekt replaces the
  toolkit's registry entry for `UnknownComponent` — the default reports and draws nothing, and a hole
  is indistinguishable from a screen that failed to load. Which density is NOMINALLY the screen's
  decision (`LocalUnknownBlockDensity`) — but nothing provides it outside the renderer's own test, so
  the CARD branch is unreachable in production and no arrangement of components demonstrates both.
  `B-25` carries the finding and the open question of who should choose. The wire name goes to the degradation sink, where
  an operator can count it; on the screen it is a word nobody can act on.
- **A screen reachable two ways must be built one way.** The eSIM wizard's activate step was served
  by a step POST and by a screen GET; only the first resolved the issued profile, so the step told
  subscribers their activation code could not be read while the database held it (`B-66`). Both paths
  now go through one `viewOf` — including the callers for which a null is currently correct, because
  "correct today" is what the two copies were before they diverged. The general rule: when a client
  posts and then REFETCHES — which `EsimInstall` does deliberately, and says so — every assertion on
  the POST's body is an assertion about a payload nothing renders.
- **A missing action module is silent, unlike a missing component module.** kompot answers an
  unregistered action with `UnknownAction`, so the tree still decodes and a test reading a control's
  action gets null — indistinguishable from a screen that drew none. The stand's `Json` was missing
  all three of ours (`B-73`) and no test noticed, because none of them read an action.
- **The stand is the only thing that asks the application.** Four defects fatal to the running server
  survived 191 green tests, because every test below that level builds its own object graph and
  supplies what it needs: a broadcaster nothing bound (the server could not start), a `Json` missing
  petich's payloads (no purchase could be created), two use cases injected and never bound (two
  screens 500), and a healthcheck running `/dev/tcp` under dash (permanently unhealthy). Run it:
  `make stand-up && make e2e`.
- **`by inject<T>()` needs a binding and Koin will not say so until the request arrives.**
  `RoutesResolveWhatTheyInjectTest` reads what the routes inject and checks the application's own
  modules bind it — by inspecting definitions rather than resolving them, because one of the bindings
  opens a socket in its constructor.
- **A feature that is built is not a feature that runs.** The usage feature shipped complete and
  tested and was installed by nothing: five imports in `Application.kt` with no use beneath them, so
  a completed purchase granted no allowance and no route could read a counter. `KoinGraphTest`
  cannot see this — it verifies the modules it is GIVEN. `FeatureModulesReachTheGraphTest` reads the
  composition root as text instead, and `WorkersAreStartedTest` does the same for a worker nobody
  starts: a binding is data and can be verified, a `start(scope)` call is control flow and cannot.
- **Never `call.respond` a component tree, and there is now a test that says so.**
  `CallRespondUsageTest` reads the sources, because `call.respond(anything)` compiles. It was proved
  to bite by rewriting a real route.
- **The client's session lives behind ktor's bearer plugin, not an interceptor.** Two things were
  believed and are false: a stored session IS attached to the very first request (the bare-first-401
  shape belongs to a session with no tokens yet), and the refresh call re-enters the plugin carrying
  the token that just failed unless it sets `attributes.put(AuthCircuitBreaker, Unit)` —
  `markAsRefreshTokenRequest()` does not exist in Ktor 3.5.
- **`Last-Event-ID` is deliberately not used.** It resumes a stream by replaying, which needs the
  server to number and keep frames; this one does neither, because an update is losable by design.
  The client reconnects and announces the gap instead, on `SseRealtimeSource.streamRestarted`, and
  `KonektApp` consumes it (`B-43`): it clears the update overlay and refetches, in that ORDER — the
  other way round the in-flight response is overwritten by the stale entry it was fetched to replace.
  Both halves are proved by mutation separately, because a test that clears and refetches passes on an
  implementation that does neither.
- **MockEngine and the client's SSE plugin do not meet.** No frame ever arrives and the collector
  waits, so the failure is a timeout that names the test rather than the cause. Stream tests run an
  embedded CIO server on an ephemeral port.
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

`make check` is the gate and CI runs exactly it — and it is green with the layers filled in.
`make report` is the two non-blocking reports. Measured on 2026-08-25, after `B-39`: `bdd_report`
counts 52 scenarios across the four features, 47 of them naming a test that exists; `code_anchors`
resolves 158 of 233 paths, skips 63 as patterns, and calls 12 rotten — **all twelve in the research
documents**, where the "anchors" are coordinates and artefacts in other repositories rather than code
in this one. A rotten anchor in `features/`, `screens/`, `api/` or `services/` means a real rename and
is worth chasing.
