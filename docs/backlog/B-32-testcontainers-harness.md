---
id: B-32
title: "Repository tests run against a real Postgres, and use cases against MockK"
status: done
priority: P0
size: S
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-32 — Repository tests run against a real Postgres, and use cases against MockK

The test harness is decided once and inherited by every feature, so it is decided before the first
feature rather than by whoever writes it. Two seams, two tools, and the split is not a preference:
`exposed-core` is JVM-only and MockK publishes `common` and `jvm` and nothing else
([research-stack](../research/research-stack.md) §1.2, §1.3).

- **The decision and its reason.** Testcontainers with the Postgres major the deployment runs, for
  every repository and route test. Mocking Exposed proves nothing — the defect a repository test
  exists for lives in the SQL, and a mock returns whatever the test put in it. H2 in Postgres
  compatibility mode is cheaper and diverges on exactly what this build leans on: `ON CONFLICT`,
  `SELECT … FOR UPDATE` beside petich's optimistic locking, and the `jsonb` column a saga payload
  lives in.
- **MockK is for the use-case layer**, where the repository interface is the seam and the use case is
  the subject. It resolves in `-server-domain` because that module targets `jvm()` only; in any module
  that also targets iOS or Android it does not resolve at all, and there the double is a hand-written
  `object : XRepository { … }`.
- **Turbine wherever a `Flow` is the subject** — the realtime source, the booblik consumer, a client
  view model. Not for a suspend function returning a value: `runTest` alone is the tool there. The
  assertion Turbine is really for is *absence* — "no further emission", which is what catches a
  duplicate update and is unwritable without it.
- A Koin graph test resolves every binding, because `singleOf(::XImpl)` resolves defaulted constructor
  parameters through the container and fails at runtime while the compiler says nothing.
- The rejected alternative is H2 everywhere for speed. It buys a suite that is green about H2.
- Not covered: performance testing. Nothing here measures anything.
- **Half delivered by `B-02`**, which could not be accepted without it: `PostgresHarness` exists —
  one Postgres 18 container for the whole test JVM, migrated by the real Flyway scripts, with
  `truncateAll()` between tests. What is left is the MockK use-case seam (no use case exists yet),
  Turbine (no `Flow` exists yet) and the Koin graph test (no graph exists yet). The harness landed
  first because a schema cannot be checked without a database, and the rest lands with the code it
  is for. `B-33` added the Koin graph test: `verify()` over each module, plus a resolve by type,
  because `verify()` over an empty module list passes and is not by itself evidence of anything.

- AC: a repository test creates its schema through the real migrations in a container and passes.
- AC: the Koin graph test fails when a binding is removed, naming the missing type.
- AC OK: CI runs the container-backed tests in a `build` job of its own, and **asks for Docker
  before Gradle does**. Without that step a runner with no daemon fails somewhere inside
  Testcontainers with a message about a socket; with it the job says what it needs. `ubuntu-latest`
  because this repository is public and GitHub-hosted runners are free for public repositories — a
  private one needs a self-hosted label, and that difference is a bill rather than a setting.
- Also: the build job is separate from the documentation gate, because the two fail for unrelated
  reasons and a contributor who broke a link should not be reading a Gradle log.
- Anchors: `server/src/test/kotlin/io/konekt/testing/PostgresHarness.kt`,
  `server/src/test/kotlin/io/konekt/di/KoinGraphTest.kt`.

Background: [research-stack](../research/research-stack.md) §1.2, §1.3, D16, D17, Risk 9.
