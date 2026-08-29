---
id: B-84
title: "Four files name DevRoutesAreNotProductionTest as what keeps the dev routes out of a real build, and there is no such test"
status: open
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
