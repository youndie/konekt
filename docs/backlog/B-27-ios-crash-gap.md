---
id: B-27
title: "Wire katcher into the iOS build, now that it has an Apple target"
status: done
priority: P1
size: S
stage: stage-m4-proof
blocked_by: [B-26, B-43]
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

## AC 1 is met: the crash happened on a simulator and the report is in katcher

What was missing was never code in this item — it was an application. `:client` is a library, and a
library cannot crash on a device. Two things supplied the rest: `B-26` seeded katcher with an
application and a key for each half of the product, and this item now has something to run.

**No Xcode project, and that is the decision worth keeping.** The ordinary route is a `.framework`
plus a project linking it — several thousand lines of `.pbxproj` that no Kotlin change can keep
correct, for an application whose entire job is to start a reporter and throw. Kotlin/Native emits a
Mach-O executable, and a simulator `.app` is a directory with an `Info.plist` and a binary in it;
`scripts/ios-crash-app.sh` assembles one. So the thing that crashes is built by the same compiler,
from the same source set, as the reporter it is testing — rather than by a toolchain kept in step by
hand.

No UI either, deliberately. None of what is proved is visual: that the reporter starts on a real
Apple target, that Kotlin/Native's unhandled-exception hook fires, and that a report reaches a
collector over the network from inside a simulator. A screen would add ways to fail that have nothing
to do with any of them.

Measured, in katcher's own database:

```
(2, 'konekt-client', 'kotlin.IllegalStateException: deliberate crash from the', 2)
releases on reports: ('ios-b27', 'simulator')
```

The release is named, which is what this item's acceptance asks for and why `KonektCrashReporter`
refuses a blank one.

## Three things the run taught, each invisible from the code

**The refusal fired first, and it was right.** The very first launch printed
`katcher appKey is blank — the iOS build would report nothing and say nothing` and died there. The
cause was `simctl`: trailing `NAME=value` arguments to `simctl launch` are arguments to the PROCESS,
not environment, and only `SIMCTL_CHILD_`-prefixed variables in simctl's own environment reach the
app. Without that refusal the run would have started a reporter pointed at nothing and looked
identical to a working one — which is the exact failure `KonektCrashReporter` exists to prevent, met
on its first real launch.

**A crash reporter cannot upload the crash it is reporting.** The process is dying. katcher saves to
disk on `catch` and uploads on the NEXT start, which is what every crash reporter does. This binary
has no run loop, so `main` throwing ends the process before the uploader finishes; it waits four
seconds before crashing, and a real application does not need that because its run loop outlives the
launch by minutes.

**And the script was deleting the evidence.** It ran `simctl uninstall` before `install`, which wipes
the app's data container — where the saved report lives. So the next launch had nothing to upload
while printing `Worker woke up. Checking disk...` to say it had looked. Two runs in a row left two
reports and delivered neither. `install` over an existing bundle replaces the binary and keeps the
container.

## Re-run on 2026-09-04, and the harness had two defects of its own

Re-verifying this item after katcher moved `0.6.2` → `0.6.41` found that the script could not run at
all, and then that it had never been able to deliver anywhere but this machine.

**It had been dead for a month, by one path.** `scripts/ios-crash-app.sh` looked for
`bin/iosSimulatorArm64/debugExecutable/KonektCrash.kexe`. A NAMED executable puts its output in
`crashDebugExecutable/`, and `e6570db` — the commit that added the second binary the day after this
item closed — moved it, wrote `ios-home-app.sh` with the right path, and left this one behind. The
script linked successfully, failed to find its own output and said so to nobody, because nothing runs
it. That is the shape `B-119` and `B-120` also have: a guard that cannot fail because it never runs.

**And ATS had been refusing every upload that was not to this machine.** With the path fixed the
crash fires and the report is stored, and nothing arrives. `KONEKT_UPLOAD_WAIT_MS=25000` is what
showed why — the window is long enough for katcher's own message:

```
📡 Transmission failed: Exception in http request: Error Domain=NSURLErrorDomain Code=-1022
   "The resource could not be loaded because the App Transport Security policy ..."
```

The hand-written `Info.plist` declares no ATS exception, so iOS refuses a cleartext `http://`
request — and **ATS exempts localhost**, which is why this was invisible: against the default
`http://127.0.0.1:8092` the harness always worked, and against any collector on another host it
never could. The bundle now carries `NSAllowsArbitraryLoads`, with the reason written beside it.

**And the sibling script had both fixes all along.** `scripts/ios-home-app.sh`, written the day after
this one, uses `homeDebugExecutable` and declares `NSAppTransportSecurity`. The two scripts assemble
the same kind of hand-written bundle, and this one's own plist carries a comment saying a launch
screen and a scene manifest are in it "so the two hand-written bundles do not differ in a way
somebody has to rediscover" — while they differed in the path to the binary and in whether the app
could talk to anything. The lesson was learned while writing the second and never carried back to
the first, which is what a pair of hand-maintained files does when only one of them is run.

**What the collector holds now**, on a stand on another host, app `konekt-client`:

| report | release | environment |
|---|---|---|
| 4, 3, 2 | `ios-katcher-0.6.41` | `simulator` |
| 1 | `ios-b27` | `simulator` |

Report 1 is **this item's own crash, from 2026-08-26**, which had been sitting in the simulator's
container undelivered for a month and went up on the first launch that was allowed to send. The
backlog of one is the evidence that the leg had never worked remotely. AC met again, on the bumped
client, and this time against a collector that is not on the machine that produced the crash.

## Still not covered

- **Symbolication of an iOS crash.** Android has the Gradle plugin uploading its R8 mapping; the
  Apple equivalent is not in this release and is its own question. The stack in the report above is
  the Kotlin/Native one, which names functions and not source lines.
- **A device rather than a simulator.** Signing, provisioning and a physical phone are a different
  problem from the one this item is about, and nothing here depends on which of the two ran it.
- **A shipped application.** This is a crash harness, not the product: no UI, no screens, no session.
  The desktop runner is where the product is assembled (`B-43`), and iOS gets one when a screen needs
  to be on a phone.
