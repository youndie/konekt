---
id: B-85
title: "The client claims Compose Multiplatform on two platforms and declares no Android target at all"
status: open
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
