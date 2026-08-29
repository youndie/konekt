---
id: B-90
title: "The iOS build runs only in a simulator: iosArm64 declares no binary and the .app is assembled by a shell script"
status: dropped
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

- AC: `linkHomeReleaseExecutableIosArm64` produces a binary — **done**, see below — and the script
  assembles a bundle that installs on a physical device with a development identity.
- AC: the home screen draws against a stand from that device, and the fact is recorded with the
  build it was done on — a claim about a device is worth nothing without one.
- AC: a deliberate crash from the device build appears in katcher, and the difference from `B-27`'s
  simulator report is stated in `research-architecture` §1.9.
- Anchors: `client/build.gradle.kts`, `scripts/ios-home-app.sh`, `scripts/ios-crash-app.sh`,
  `client/src/iosMain/kotlin/io/konekt/client/ios/HomeEntryPoint.kt`,
  `client/src/iosMain/kotlin/io/konekt/client/observability/KonektCrashReporter.kt`.

## Closed as a boundary, not as work

**There is no Apple account and there will not be one.** Installing on a phone needs a development
team; the simulator needs none. So the three acceptance criteria that name a physical device cannot be
met here, and this item takes the alternative it wrote down for itself:

> The rejected alternative is to state the simulator as a boundary and stop. That is defensible and it
> is what happens if this is dropped; it should then be written into `B-80` rather than left implicit.

It is now a row in [reference-scope](../services/reference-scope.md), with the reason and with what
would end it — an Apple ID with a development team, and an install through `xcrun devicectl`.

**Dropped rather than done**, and the mark matters: the work is refused, not finished. The file stays
so the same proposal is refused in ten seconds the next time it comes up.

### What this build may and may not say about Apple, from now on

| May say | May not say |
|---|---|
| the Apple half **compiles**, for the simulator and for `arm64` | that anything **runs** on a phone |
| a simulator crash reaches katcher naming its release (`B-27`) | that a crash from an arm64 device reaches it — a simulator crash is a Mach-O process on macOS |
| the screens are drawn by the same registry the other platforms use | that they have been seen on iOS hardware |

`README.md`'s observability table already says *a simulator crash arrives naming its release*, which
is exactly this precise, and needs no change.

## What was done anyway: the half that needed no hardware

`iosArm64` had **no `binaries` block at all**, so nothing was ever linked for a phone — the target was
declared, compiled as a klib, and produced no executable. It now declares the same two the simulator
gets, and both link:

```
client/build/bin/iosArm64/homeDebugExecutable/KonektHome.kexe    62 MB   Mach-O 64-bit executable arm64
client/build/bin/iosArm64/crashDebugExecutable/KonektCrash.kexe  41 MB   Mach-O 64-bit executable arm64
```

That is the first acceptance criterion and it needed no device. What it buys is that the remaining
three are questions of **signing and installing** rather than of whether this code builds for a phone
at all — and the answer to that was not known before: `AppleTestsAreNotClaimedTest` guards what is
claimed about Apple TESTS, and nothing guarded what was claimed about an Apple BINARY.

**The rest is refused rather than pending.** A development identity, an install through `devicectl`,
the home screen drawn from the device and a crash reaching katcher from it all need an account that
does not exist — and none of them may be inferred from the simulator, which is the whole point of this
item and the reason it becomes a boundary instead of quietly closing.

The two bundle fixes the simulator run turned up on the way (`B-94`'s launch screen and `B-95`'s scene
manifest) apply to the device bundle too: both are in the same `Info.plist` the script writes.
