---
id: B-37
title: "No iOS test is executed by anything, and the build says nothing about it"
status: open
priority: P1
size: S
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-37 — No iOS test is executed by anything, and the build says nothing about it

Measured on the skeleton itself, with a throwaway source file so the tasks had something to do
([research-stack](../research/research-stack.md) §1.7). On the Linux box the three Apple compile
tasks run and the four Apple test and link tasks report `SKIPPED` inside a `BUILD SUCCESSFUL`. On the
Mac the same test task fails at `Xcode does not support simulator tests for ios_simulator_arm64` —
`xcrun simctl list runtimes` is empty.

So the Apple code compiles on every build and is executed by nothing, and neither half announces
itself: a skipped task and a passing task look the same in a summary line.

- **The decision and its reason.** The iOS test run is a **separately named command** in `CLAUDE.md`
  and its own CI job, never a thing `build` is assumed to have covered. A check folded into a target
  that succeeds without it is a check that quietly stops existing.
- **The runtime is the machine owner's call, not a build step.** `xcodebuild -downloadPlatform iOS`
  is several gigabytes; it is asked for, not run silently.
- **A job that is always red proves nothing and hides real breakage**, so the iOS CI job is added
  *when* a runner can run it, and until then this item stays open and named rather than being a
  green workflow that never executed a test.
- The rejected alternative is `-x iosSimulatorArm64Test` in the build invocation, which makes the
  build honest about nothing while looking tidier.
- Not covered: crash reporting on iOS. That is `B-27` and youndie/katcher#25 — a different gap, and
  the two compound: an iOS defect is caught by nothing before release and reported by nothing after.

- AC: a named command runs the iOS tests on a Mac with a runtime installed, and fails when one of
  them fails.
- AC: `CLAUDE.md` states that a green `wsl-run ./gradlew build` covers Apple compilation and not
  Apple tests.
- AC: while no runner has a simulator runtime, no CI job claims to run these tests.
- Anchors: `CLAUDE.md`, `.github/workflows/`, `build-logic/src/main/kotlin/konekt.multiplatform.gradle.kts`.

Background: [research-stack](../research/research-stack.md) §1.7,
[research-architecture](../research/research-architecture.md) §1.9.
