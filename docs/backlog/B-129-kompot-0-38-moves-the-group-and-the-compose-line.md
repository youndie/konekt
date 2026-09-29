---
id: B-129
title: "kompot 0.38.0 moves its group and its Compose line, and konekt is its first consumer"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-129 — kompot 0.38.0

kompot released `0.38.0` on Maven Central on 2026-09-25 and nobody had taken it. konekt is its
reference consumer, so this move is as much a test of the release as an upgrade: whatever the
upgrade notes leave out, this repository is where it shows first.

What the release asks of a consumer, read from its `UPGRADING.md` and checked against what is
published:

- **A new group for every artefact** — `io.github.youndie` becomes `io.github.youndie.kompot`, the
  BOM, `form-core`, `form-standard` and `wizard-core` included. Kotlin packages do not move.
- **The Compose half on Compose Multiplatform 1.12.** The client moves with it in one commit —
  `1.12.1`, material3 `1.12.0-alpha03`, viddik `0.6.1` — because mixing lines resolves, compiles and
  throws inside a renderer (research-stack §1.10).
- **Android `compileSdk` 37**: every 0.38 AAR carries `minCompileSdk = 37`.
- **`KompotDegradationSink`'s third parameter is `KompotDegradationOutcome`** where it was
  `drawnAsFallback: Boolean`.
- Two changes that compile unchanged and are recorded here so nobody goes looking: impressions count
  by visibility (konekt tracks none), and `Row`/`Column`/`Button`/`Text` gained trailing defaulted
  parameters (a recompile is all it takes).

## Acceptance

- Every kompot coordinate is under `io.github.youndie.kompot` at `0.38.0`, through the BOM, and
  `./gradlew build` is green on the Linux box.
- The degradation record says which of the three outcomes happened, and a mutation that reports the
  wrong one is caught.
- The stand agrees: `make stand-up && make e2e` green.

## Findings

**Kotlin and AGP did not have to move.** kompot is built with Kotlin 2.4.20 and AGP 9.4.1; konekt
stays on 2.4.10 and 9.3.1 and compiles, links and tests against it. kotlin-stdlib resolves to 2.4.20
because kompot's metadata asks for it, which the 2.4.10 compiler accepts.

**Checked where each half runs.** Linux box: `./gradlew build` green (the Apple klibs compile there
and nothing Apple runs), then `make stand-up && make e2e` green — 38 e2e and 16 client-on-stand
tests. Mac: `:shared:components:iosSimulatorArm64Test` (7), `:client:iosSimulatorArm64Test` (5) and
`:client:linkHomeDebugExecutableIosSimulatorArm64` green, so the Compose 1.12 half links for a
simulator. Mutation: reporting `SERVER_FALLBACK` from `UnknownBlockRenderer` fails
`UnknownBlockRendererTest`, `ClientAgainstStandTest` and `DegradationReachesTracyTest`.

**The Java 25 rationale is gone.** CLAUDE.md said Java 25 was forced because kompot and petich
published `org.gradle.jvm.version = 25`. kompot 0.38.0 declares 17 and petich 0.4.0.112 declares 21.
The toolchain stays 25, and the sentence now says it is a choice.

**`ktor` stays on 3.5.2, but the reason written beside it has expired.** It was held because okhttp
5.5.0 refuses compileSdk below 37; compileSdk is 37 now. The bump is its own change.

**Twenty-two goldens changed, all in the same place: the last pixel row of the frame and nothing
else.** Eleven cases, each in both themes — the gallery's Home, Plans, Plan detail, Orders and
Profile in both brands, and the recorded home. The old goldens carried a half-transparent row along
the bottom edge (`(215,227,223)` at alpha 128 over the background, blended into the content across
the width); the new ones draw the content there instead. No other pixel moved on the Mac recording;
on the Linux run `B Orders` also differed in five antialiased pixels near the top, inside tolerance.
This matches viddik 0.6's capture change (youndie/viddik#37, "render auto-height captures at the
measured height") rather than anything in the screens: every frame that changed is one whose
content runs past the frame's bottom edge. Recorded on the Mac; `viddikRecord` wrote exactly the
eleven cases the verification rejected and kept the other twenty-five.

**The committed schema gained `"format": "float"` on three float fields** (`usage_counter_card`'s
`progress`, and `viewportSize` and `strokeWidth` on the vector icon). That is kompot-spec's generator, since 0.37.0 — which
has no upgrade notes, and konekt skipped it.

**One compile of `:client:compileCommonMainKotlinMetadata` failed and did not come back.** The first
build after the bump reported `Unresolved reference 'wizard'` for
`io.github.youndie.kompot.wizard.core.WizardTransition` in `EsimInstall.kt`. The dependency report
resolved `wizard-core:0.38.0` on that classpath, the metadata jar and its `.module` have the same
shape as 0.36.1's, and the same task with `--rerun-tasks` passed, as did every build after it.
Stale incremental state from the group change is the likeliest reading; it is written down because a
clean CI run is the only thing that cannot have it.

**The next step is `B-116`, and 0.38 covers about half of it** (kompot's B-51, SPEC §12.5, read at
the `v0.38.0` tag). What it gives: `present` with `kind = "sheet"` draws a tree in a Material
`ModalBottomSheet` over a screen that stays mounted, a drag or the back gesture dismisses it, and
`close` inside it closes the sheet and goes no further. What `B-116` still needs that it does not
give:

- **No degradation to today's screen.** A client that predates `present` meets `UNKNOWN_ACTION` and
  the tap does nothing (§12.5 says so outright); actions carry no `fallback` the way components do,
  so the server cannot say "a sheet, or else navigate to the confirmation screen". `B-116` asks for
  exactly that fallback.
- **Only Material can draw it.** `KompotOverlayHost` lives in `kompot-ds-material-compose` and draws
  stock `ModalBottomSheet`/`Dialog`; `KompotOverlays.presented` and `asking` are `internal`, so a
  design system of konekt's own cannot read what to draw and would have to reimplement
  `withOverlays` too. The sheet's handle, shape and colours are Material's, not the canvas's.
- **The sheet does not end with the screen.** `withOverlays` forwards every other action untouched,
  so a `Pay` inside the sheet that answers with a navigation leaves the sheet open over the next
  screen — unless the application keys its `KompotOverlays` to the screen, or the server answers
  `sequence[close, navigate]`. Read from the code, not run.
- A dismissal raises nothing — which suits `B-116`, because leaving the confirmation is meant to be
  silent (the order rolls itself back at its deadline).

## Anchors

| What | Where |
|---|---|
| The versions | `gradle/libs.versions.toml` — `kompot`, `composeMultiplatform`, `composeMaterial3`, `viddik`, `androidCompileSdk` |
| The android-variant list | `client/build.gradle.kts` — `androidVariantExpected` |
| The degradation record | `client/src/commonMain/kotlin/io/konekt/client/app/KonektDegradationSink.kt` |
| The goldens | `client/src/jvmTest/snapshots/` |
| The schema | `shared/spec/schema/konekt-components.schema.json` |
