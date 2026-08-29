---
id: B-84
title: "Four files name DevRoutesAreNotProductionTest as what keeps the dev routes out of a real build, and there is no such test"
status: done
priority: P1
size: S
stage: stage-m6-reframe
---

# B-84 — The comment naming the guard is doing the guard's job

Three development routes carry a comment that says what stops them shipping:

```
// … with `DevRoutesAreNotProductionTest` keeping it off a real build
        server/src/main/kotlin/io/konekt/roaming/dev/ArriveRouting.kt
        server/src/main/kotlin/io/konekt/screens/dev/FailingRouting.kt
        server/src/main/kotlin/io/konekt/screens/dev/ForwardCompatRouting.kt
        client/src/jvmTest/kotlin/io/konekt/client/app/EntryPointsDoNotUseDevRoutesTest.kt
```

`DevRoutesAreNotProductionTest` does not exist. A grep over every `.kt` in the repository returns
those four comments and nothing else.

What actually holds the line is narrower than the citation and lives somewhere nobody would look for
it: `ForwardCompatScreenTest` asserts `devScreensRouteGroup !in konektRoutes` — one group, inside a
test named after a screen. `devOtpRouteGroup` has no equivalent assertion at all, and the routes
themselves are mounted behind exact-string environment flags in `Application.kt`.

The routes are worth the guard. `/api/v1/dev/fail` returns 500 by construction — the file calls it a
denial-of-service primitive if it ships. `/api/v1/dev/roaming/arrive` is public and takes
`subscriberId` from the **query rather than the token**, so with `DEV_SCREENS` on, anybody can start
a stranger's roaming package and spend their allowance.

- **The decision: write the test the comments already promise, over both groups and over anything
  under a `dev` package, then leave the comments alone.** The comments are correct about the design;
  what is missing is the thing they name.
- **Do not delete the comments instead.** They describe the intended arrangement, and four files
  agreeing on a guard's name is most of the work of having one.
- **The test asserts on the route table, not on the flags.** A flag test proves the default is off;
  what matters is that no dev group is reachable in the production composition however the flags are
  read — that is what `productionRouteGroups()` is for, and the assertion belongs against it.
- This item does **not** change what the dev routes do or how they are gated, and does not remove
  `arrive`'s query parameter — see [B-88](B-88-roaming-starts-through-a-dev-route.md), which removes
  the reason it exists.

- AC: `DevRoutesAreNotProductionTest` exists, covers `devScreensRouteGroup` **and**
  `devOtpRouteGroup`, and fails when either is added to the production route table.
- AC: the test is proved by mutation — adding a dev group to `productionRouteGroups()` turns it red.
- AC: no comment in the tree names a test that does not exist; a grep for the citations resolves.
- Anchors: `server/src/main/kotlin/io/konekt/Application.kt`,
  `server/src/main/kotlin/io/konekt/screens/dev/FailingRouting.kt`,
  `server/src/main/kotlin/io/konekt/roaming/dev/ArriveRouting.kt`,
  `server/src/test/kotlin/io/konekt/screens/ForwardCompatScreenTest.kt`.

## What was done

`server/src/test/kotlin/io/konekt/DevRoutesAreNotProductionTest.kt`, three assertions:

1. **No development route is in the production route table.** It mounts `productionRouteGroups()`
   into an application, walks the routing tree Ktor actually built, and refuses any path under
   `/api/v1/dev/`. Not the flags: a flag test proves one configuration file's default, and what
   matters is that no dev route is reachable in the composition every deployment mounts, however the
   flags are read.
2. **The positive control**, which is what keeps the first from being a test of a detector that finds
   nothing: mounting production **plus** both dev groups produces exactly four routes, named
   individually — `forward-compat`, `roaming/arrive`, `fail` and `otp`. Asserting the development
   table is merely larger would pass with either group missing.
3. **Every `@Resource` under a `dev` package spells a `/api/v1/dev/` path.** The first check reads a
   path, so a dev route whose path did not say `dev` would be mounted into production and greeted
   with silence. This one reads the source, over every module — `productionSources()` walks the
   repository — with a count so a file leaving a `dev` package is noticed rather than silently
   dropping a subject.

**Proved by mutation.** `productionRouteGroups() + devScreensRouteGroup` turns it red, naming the
three routes it now serves: `GET /api/v1/dev/fail`, `GET /api/v1/dev/screens/forward-compat`,
`POST /api/v1/dev/roaming/arrive`.

### Two more of the same shape, found by the third AC

The AC asked that a grep for the citations resolve. It did not, for three more names:

- **`Shell.kt` cited a navigation test twice, in the present tense**, after `B-49` deleted it along
  with the client's copy of the graph. Now `EveryScreenIsReachableTest`, which is what replaced it.
- **Two files named a topic test that has always been called `BrokerTopicsTest`** — `EventTopics.kt`
  and `BrokerHarness.kt`.
- **`KonektClientJson.kt` and `EveryScreenIsReachableTest.kt` each quoted a name deliberately**, one
  for a test that never existed and one for a test `B-49` deleted. Both now describe the test instead
  of spelling its name.

That last decision is the reason there is a **fourth deliverable**: `CitedTestsExistTest`, which fails
on any backticked `…Test` in the tree that is not a file. It found the second historical citation
while being written, which is the argument for it. It carries **no exemption list** — the two
deliberate references were reworded rather than exempted, because an exemption in a completeness guard
is one line away from covering the case the guard exists for, and its own regex is built from two
pieces so this file contains no citation of itself.

### What moved

- `routingTreeOf`, `inventoryOf` and `servedBy` came out of `OpenApiDocumentTest` into
  `openapi/RoutingTreeHarness.kt`. Two guards now ask the same question of the route table and a
  second copy of that harness would be a second opinion about what a deployment mounts.
- `ForwardCompatScreenTest`'s `devScreensRouteGroup !in konektRoutes` is **deleted**, with a comment
  saying where it went and why the new one is stronger: object identity over one group satisfied any
  dev route mounted by other means.
- `productionSources()` is now a filter over a new `everyKotlinSource()`, because a comment citing a
  test lives in a test as often as in production code.

Verified: `:server:test` and `:client:jvmTest` green, `ktlintCheck` green.

## What is deliberately not in scope

What the dev routes do and how they are gated. `arrive`'s `subscriberId` query parameter is still
there — [B-88](B-88-roaming-starts-through-a-dev-route.md) removes the reason it exists rather than
authenticating a route that must never ship.

## Anchors

| What | Where |
|---|---|
| The guard | `server/src/test/kotlin/io/konekt/DevRoutesAreNotProductionTest.kt` |
| The one that keeps the citations honest | `server/src/test/kotlin/io/konekt/ci/CitedTestsExistTest.kt` |
| The shared route-table harness | `server/src/test/kotlin/io/konekt/openapi/RoutingTreeHarness.kt` |
| What it asserts about | `server/src/main/kotlin/io/konekt/Application.kt` (`productionRouteGroups`) |
| The routes it keeps out | `server/src/main/kotlin/io/konekt/screens/dev/`, `server/src/main/kotlin/io/konekt/roaming/dev/` |
