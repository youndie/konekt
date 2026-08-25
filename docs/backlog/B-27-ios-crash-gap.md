---
id: B-27
title: "Wire katcher into the iOS build, now that it has an Apple target"
status: wip
priority: P1
size: S
stage: stage-m4-proof
blocked_by: [B-26]
---

# B-27 — Wire katcher into the iOS build, now that it has an Apple target

`katcher:client:0.5.1` published `jvm` and `linux_x64` and nothing else, so the iOS half of this
application reported nothing. This item was going to be a paragraph admitting that.

**katcher#25 closed on 2026-08-25**, and `client:0.6.2` publishes `ios_arm64`,
`ios_simulator_arm64`, `ios_x64`, both macOS targets, both Linux targets, `mingw_x64` and `jvm` — the
host-picked native target is gone too. So the item is wiring, not documentation.

- **The decision and its reason.** The Apple client reports to the same katcher the server does, with
  the release and environment it was built with. The gap was worth refusing to paper over with
  another vendor's SDK — that refusal is what made it legible enough to file, and filing it closed it
  in a day.
- Uncaught-exception capture on Kotlin/Native goes through `setUnhandledExceptionHook`, which is a
  different mechanism from the JVM's `Thread.UncaughtExceptionHandler`; it does not come free with
  the target declaration, and it is the part to check by actually crashing the app.
- The rejected alternative is trusting that a published target means a working reporter. A published
  klib proves it links, not that a crash on a device reaches the server.
- Not covered: symbolication of an iOS crash. Android has the Gradle plugin uploading its R8 mapping;
  the Apple equivalent is not in this release and is its own question.

- AC: a deliberate crash in the iOS build produces a report in katcher naming the release.
- AC: the README's observability section says what is covered on which platform, and matches.
- Anchors: `client/build.gradle.kts`, `client/src/iosMain/kotlin/io/konekt/observability/`,
  `README.md`.

Background: [research-architecture](../research/research-architecture.md) §1.9 (D8 withdrawn),
[research-upstream-proposals](../research/research-upstream-proposals.md#u5).

## What landed

**`:client` builds for iOS at all**, which it could not before today. kompot#84 closed and released in
`0.31.0.76`, and the fix was checked here rather than read off the issue: at `0.32.0.77` the module
metadata of `kompot-client`, `kompot-theme-client` and `kompot-ds-material-compose` each declares
`ios_arm64` and `ios_simulator_arm64`. Two targets, not three — Compose dropped `iosX64` — so the
module names its own rather than taking `konekt.multiplatform`'s list. The reason it is not the
convention plugin changed completely while the conclusion did not.

katcher is wired into `iosMain`, and the first Apple test this repository has ever executed runs on
the simulator: `LOCAL=1 ./gradlew :client:iosSimulatorArm64Test`, three cases, proved by mutation.

## The hook did not need writing, and that is a deviation from this item

This item says the uncaught-exception hook is "the part to check by actually crashing the app", on the
grounds that `setUnhandledExceptionHook` is a different mechanism from the JVM's handler and does not
come free with a target declaration. It does not come free with the *target*; it comes free with
`Katcher.start()`. Read in the sources of `client:0.6.2`: `nativeMain` carries an
`internal actual fun setupPlatformHandler()` that installs the hook, keeps whatever hook was there
before, calls it afterwards, and falls back to `terminateWithUnhandledException` when there was none.

So what was left for konekt is the part the library cannot do: **refuse to start wrong.**
`Katcher.start` answers a missing appKey or host with a `println` and a return, which leaves a build
that MEANT to report indistinguishable from one that is reporting — both are silent, and the
difference surfaces when somebody goes looking for a crash that was never sent. `KonektCrashReporter`
refuses instead, where it is configured, and refuses a blank `release` too: `KatcherConfig.release`
defaults to the string "Unspecified", and a crash group that cannot name its build is one nobody can
act on. That is why this item's acceptance asks for a report NAMING the release.

## `wip`, and precisely why

AC 2 is met — the README carries a platform table, and it says "not wired yet" for the three server
rows because that is true.

**AC 1 cannot be met from here, and it needs two things this repository does not have**, neither of
which is a line of code in this item:

- **an iOS application to crash.** `:client` is a library: there is no `App.kt`, no entry point and no
  Xcode project. B-22 met the same wall from the other side — its brand kit has nothing to fetch it.
- **a katcher to receive.** There are nine applications registered in the katcher this account runs
  and konekt is not among them, and the compose stand runs no katcher. That is `B-26`, which this item
  already declares as its blocker, and it turns out to be a real dependency rather than a formality.

What is proved without them: the reporter links and runs on a real Apple target, and it refuses to
start in the three configurations that would make it silently useless. What is not proved is delivery.
Saying that plainly is the point — an observability agent that is switched off looks exactly like one
that is working.
