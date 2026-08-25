# CLAUDE.md — konekt

A white-label subscriber account for an eSIM MVNO: Ktor on Kotlin/JVM, Compose Multiplatform on
Android and iOS, backend-driven UI. Built as a reference for six toolkits — kompot, petich, booblik,
katcher, metrik, tracy — with every external system (BSS/OCS, SM-DP+, payments, SMSC) mocked.

Gradle 9.7.1, Kotlin 2.4.10, Ktor 3.5.2, Koin 4.2.2, Exposed 1.4.0, Postgres. Java 25 is mandatory
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
   `docs/services/`. **These four are empty today**, and that is the current state rather than an
   oversight: there is no code, and a document written ahead of code documents intent as fact.

For anything touching the interface, [docs/design/design-app-canvas.md](docs/design/design-app-canvas.md)
carries the component dictionary and the three frames that describe states a naive implementation
cannot reach.

## Rules that are cheap to follow and expensive to discover

- **Never `call.respond` a `KompotComponent`.** It drops the `"type"` discriminator on the root of the
  tree; nested children serialise perfectly, which is what makes it easy to miss, and the client then
  receives an unknown component for the whole screen and draws nothing. Use
  `call.respondKompotComponent`.
- **Never name a kompot version.** One `platform("io.github.youndie:kompot-bom")` and no version on
  any kompot coordinate. The tail digit of a version is the CI run number, so two coordinates one run
  apart resolve into a combination nobody ever built.
- **katcher is three version lines**, not one: server, `client`, and `client-android` plus the Gradle
  plugin. They are separate entries in the catalogue and the catalogue says why.
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
- **`suspendRunCatching`, never `runCatching`, in suspend code.** Plain `runCatching` swallows
  `CancellationException`; the symptom is a request that will not stop, and nobody attributes it to
  this.
- **Never wrap a `Result` in a `Result`.** A repository unwraps what it gets from below and throws a
  domain exception; a `failure` nested inside a `success` travels past the error handler in silence.
- **`io.ktor.server.cio.CIO` collides by name with the client's `CIO`.** Any file that also builds an
  `HttpClient` needs an import alias, or `embeddedServer` receives the client engine.
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
