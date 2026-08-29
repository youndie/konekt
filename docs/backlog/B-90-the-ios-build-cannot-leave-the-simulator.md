---
id: B-90
title: "The iOS build runs only in a simulator: iosArm64 declares no binary and the .app is assembled by a shell script"
status: open
priority: P2
size: M
stage: stage-m7-completeness
---

# B-90 — Everything Apple here is true of a simulator and of nothing else

`client/build.gradle.kts` declares `iosArm64()` with **no `binaries` block** and
`iosSimulatorArm64 { binaries { executable("crash"); executable("home") } }`. The runnable artefact
is assembled by `scripts/ios-home-app.sh`: it links the simulator executable, writes an `Info.plist`
by hand, and installs through `xcrun simctl`. `HomeEntryPoint.kt` says what that leaves out — *no
launch screen, no icon, no signing, no App Store metadata* — and the plist carries
`NSAllowsArbitraryLoads`, which is right for a stand and nothing else.

The absence of an Xcode project is a good decision and should survive this item: `client/build.gradle.kts`
argues it well — several thousand lines of `.pbxproj` that no Kotlin change can keep correct. What
does not survive is what it currently costs: **no statement about this client is true of a phone.**

Three specific claims are affected, and only the third is severe:

- the screens are photographed on the JVM and drawn in a simulator — fine, and honest;
- `iosSimulatorArm64Test` runs on the Mac and is skipped on Linux, which `CLAUDE.md` already
  separates carefully;
- **katcher's iOS reporting was closed on a simulator crash** ([B-27](B-27-ios-crash-gap.md)). A
  simulator crash is a Mach-O process on macOS. That it arrives says the reporter links and posts;
  it does not say a device crash arrives, and symbolication was already recorded as out of scope.

- **The decision: give `iosArm64` a binary and produce a device build, keeping the script-assembled
  bundle rather than adopting an Xcode project.** Signing is what a device needs beyond linking, and
  a development-signed bundle installed through `xcrun devicectl` is the smallest thing that makes
  the claim true.
- **What it buys is one sentence changing from "compiles" to "runs":** the Apple half of six
  toolkits — kompot's Compose client, katcher's Apple target, tracy's three iOS targets — is
  currently demonstrated on a platform that shares an architecture with a phone and not a runtime
  with one.
- **The rejected alternative is to state the simulator as a boundary and stop.** That is defensible
  and it is what happens if this is dropped; it should then be written into
  [B-80](B-80-the-non-goals-are-nowhere.md) rather than left implicit.
- This item does **not** cover the App Store, an icon, a launch screen, symbolication, or CI — no
  runner here has a device, and `AppleTestsAreNotClaimedTest` exists precisely so no job pretends
  otherwise.

- AC: `linkHomeReleaseExecutableIosArm64` produces a binary, and the script assembles a bundle that
  installs on a physical device with a development identity.
- AC: the home screen draws against a stand from that device, and the fact is recorded with the
  build it was done on — a claim about a device is worth nothing without one.
- AC: a deliberate crash from the device build appears in katcher, and the difference from `B-27`'s
  simulator report is stated in `research-architecture` §1.9.
- Anchors: `client/build.gradle.kts`, `scripts/ios-home-app.sh`, `scripts/ios-crash-app.sh`,
  `client/src/iosMain/kotlin/io/konekt/client/ios/HomeEntryPoint.kt`,
  `client/src/iosMain/kotlin/io/konekt/client/observability/KonektCrashReporter.kt`.
