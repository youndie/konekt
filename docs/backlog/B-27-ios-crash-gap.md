---
id: B-27
title: "Wire katcher into the iOS build, now that it has an Apple target"
status: open
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
