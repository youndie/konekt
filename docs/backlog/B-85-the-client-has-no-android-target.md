---
id: B-85
title: "The client claims Compose Multiplatform on two platforms and declares no Android target at all"
status: done
priority: P0
size: L
stage: stage-m7-completeness
---

# B-85 — Half of the multiplatform claim has never been compiled

`androidTarget` appears in no `*.gradle.kts` in this repository. Neither does an
`AndroidManifest.xml`, an `applicationId`, or a use of the AGP plugin that `gradle/libs.versions.toml`
declares. `settings.gradle.kts` says it outright — *ANDROID IS NOT HERE YET, deliberately* — and
`client/build.gradle.kts` names the trigger: *Android joins with the item that first needs an `.aar`*.

This is that item, and the reason is the reframe. As a box, Android was a distribution question and
could wait for a buyer. As a **reference implementation of Compose Multiplatform on a
backend-driven wire**, the claim is that one component registry draws the same server-built screens
on both platforms — and that claim is currently compiled on one.

What makes it more than a build-file line, and why this is `L` rather than `S`:

- **kompot's Android artefact is the thing being demonstrated.** Its README records the failure this
  target exists to avoid: with no android variant published, an Android consumer silently resolved
  the **desktop** one and shipped a client built against desktop Compose. konekt is the second
  implementation that can confirm the `.aar` now arrives, and only by asking for it.
- **katcher has an Android client and a Gradle plugin nothing here uses** — `KATCHER_BUILD_UUID`,
  the mapping upload, breadcrumbs from screen transitions. Three of the six toolkits have an Android
  half; the reference exercises none of it.
- **The layer rules bite here.** MockK publishes `common` and `jvm` only, so any test in a module
  that also targets Android takes a hand-written double — `research-stack` §1.3. That is a known
  cost, not a surprise, and it is worth confirming rather than assuming.

- **The decision: add `androidTarget()` to `:client`, an application module thin enough to be
  honest, and one proof that runs.** The proof is the point — a target that compiles and an
  application nobody launched would repeat exactly the mistake
  [B-16](B-16-traffic-simulator.md) recorded: a chain that is tested and never started.
- **The rejected alternative is an `.aar` with no application.** It answers the packaging question
  and not the claim; nothing draws a screen.
- **The rejected alternative is `konekt.multiplatform`.** The convention plugin declares three iOS
  targets and Compose has two — `client/build.gradle.kts` explains why this module names its own,
  and adding Android does not change that.
- This item does **not** cover the stores, signing, an icon, or `B-90`'s device build for iOS. It
  covers: the target, an application that starts, one screen drawn from the stand, and a crash
  reaching katcher the way [B-27](B-27-ios-crash-gap.md) did for iOS.

- AC: `./gradlew :client:assembleDebug` (or the module's equivalent) produces an APK, and it is
  built by CI on the Linux box like everything else.
- AC: the application signs in against `make stand-up` and draws the home screen, with the same
  registry as the JVM and iOS entry points — no second renderer map.
- AC: `kompot-client-android` is what resolves, verified from the dependency report rather than
  assumed, and the finding is written into `research-architecture` §1.9 beside the iOS one.
- AC: a deliberate crash from the Android build appears in katcher naming its release, the way
  `B-27` closed for iOS.
- Anchors: `client/build.gradle.kts`, `settings.gradle.kts`,
  `build-logic/src/main/kotlin/konekt.multiplatform.gradle.kts`, `gradle/libs.versions.toml`,
  `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt`,
  `.github/workflows/check.yaml`.

## What was done

Three of the four acceptance criteria are met and **the fourth is refused by an upstream gap**, which
is recorded rather than worked around. Taking them in order.

**AC1 — an APK, built by CI on Linux.** `androidApp-debug.apk`, 17 MB, `io.konekt.android`, minSdk 26,
targetSdk 36. `./gradlew build` now includes `:androidApp:assemble`, and the workflow installs the
platform by NUMBER rather than inheriting whatever the runner image carries.

**AC2 — it signs in against a stand and draws the home screen, with the same registry.** Done on a
**physical Pixel 6a**, not an emulator: sign-in through the server's own login screens, the code from
the stand, and the home screen with the balance card, the msisdn, the two controls, the empty-plan
card and the four-tab bar. Screens, palette and bar are the server's.

**AC3 — `kompot-client-android` is what resolves, verified from the dependency report.**

```
Variant androidApiElements-published:
  org.gradle.libraryelements                | aar
  org.gradle.jvm.environment                | android
  org.jetbrains.kotlin.platform.type        | androidJvm
```

kompot's README records an Android consumer silently resolving the DESKTOP variant. konekt is the
second implementation able to check, and the `.aar` arrives — a gap closed and *confirmed* rather than
assumed. Written into `research-architecture` §1.9.

**AC4 — a crash in katcher naming its release: NOT MET, and it cannot be from this side.** The
measurement is on a device, in the log, and in `CrashActivity`:

```
E System     : Ignoring attempt to set property "user.dir" to value "/data/user/0/…/cache".
I System.out : 📡 Katcher initialized. Storage ready.
E AndroidRuntime: FATAL EXCEPTION: main … deliberate crash from the konekt Android build
I System.out : 📡 Failed to save crash report: /.katcher_cache/crash_….json: ENOENT
```

katcher's multiplatform `client` publishes no android variant, so this build resolves `client-jvm`,
whose report cache is fixed at `System.getProperty("user.dir")` — `/` on Android, unwritable, and a
property Android **refuses** to let an application change. The other artefact, `client-android`,
declares the same `object Katcher` in the same package and fails `checkDebugDuplicateClasses` against
the one the shared code compiles against. One cannot be used and the other cannot be repaired from
outside. [katcher#27](https://github.com/youndie/katcher/issues/27), with the device log.

The hook itself works — `Thread.setDefaultUncaughtExceptionHandler`, which Android honours. Everything
works except the last step, and `start` says *"Storage ready"* having checked nothing, which is why
this took a device to find. The harness is **kept**, and `README.md`'s observability table carries the
Android row as **not delivered** with the reason: a blank cell reads as "not tried".

## What the item did not ask for and the work required

- **The composition root existed twice and had drifted.** The desktop runner handled `SignOutAction`;
  the iOS one did not, so signing out worked on a laptop and printed "no handler" on a phone. Android
  would have been the third copy, so the root moved to `KonektComposition` in `commonMain` — engine,
  settings source and platform name are the parameters, and everything else is shared.
  `KonektCrashReporter` moved with it; nothing in it was ever Apple-specific.
- **Window insets.** Without them the first element of every screen draws under the status bar: the
  login title read as a damaged font and the home title as a clipped logo, while everything below was
  perfect. Found by screenshot, fixed with `safeDrawing`, and confirmed by the same build on the same
  device.

  **And the fix's comment claimed too much, which the simulator caught later.** It said `safeDrawing`
  is "the right answer on all three" targets. On iOS `ComposeUIViewController` already insets its
  content, so the frame doing it too applied the same padding twice — the login title sat 55 device
  pixels lower with it than without, in two builds minutes apart. It is a parameter now, false on iOS
  only. A claim about three platforms verified on two is exactly the shape this item exists to end.
- **Android host tests.** AGP warns that `commonTest` exists and is not run, and a target that
  compiles without running its shared tests is the Apple gap arriving by a different door.
  `withHostTest {}` turns them on — `ClientDecodesEveryActionTest` now runs on Android, two cases, in
  `testAndroidHostTest`. It needed two task-dependency edges declared, because AGP's lint model and
  lint analysis both read KSP's output without saying so and Gradle refuses the race.
- **Two build findings worth the comment they carry**: AGP on `build-logic`'s classpath alone splits
  it from KSP's, and configuring any task dies with `ClassNotFoundException` naming an AGP class; and
  AGP 9 refuses `org.jetbrains.kotlin.android` outright.
- **`ANDROID_HOME` is now needed for any Gradle task on the Mac, the formatter included.** In
  `CLAUDE.md`, with why it is an environment variable and not `local.properties`: that file would
  replicate to the Linux box and override the SDK path already exported there.

## What is deliberately not in scope

The stores, signing, an icon and a launch screen — non-goals in
[reference-scope](../services/reference-scope.md). `B-90`'s iOS device build is a separate item and
untouched. katcher's Gradle plugin and its R8 mapping upload are pointless while no report arrives at
all; they belong with whatever closes `katcher#27`.

## Anchors

| What | Where |
|---|---|
| The application | `androidApp/` |
| The shared composition root | `client/src/commonMain/kotlin/io/konekt/client/app/KonektComposition.kt` |
| The target and its two lint edges | `client/build.gradle.kts` |
| Every shared module's target | `build-logic/src/main/kotlin/konekt.multiplatform.gradle.kts` |
| The classpath finding | `build.gradle.kts` (root `plugins` block) |
| The katcher measurement | `androidApp/src/main/kotlin/io/konekt/android/CrashActivity.kt`, `docs/research/research-architecture.md` §1.9 |
