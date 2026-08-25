---
id: B-01
title: "Gradle skeleton on Java 25, with the six dependency lines pinned separately"
status: open
priority: P0
size: M
stage: stage-m0-wire
---

# B-01 — Gradle skeleton on Java 25, with the six dependency lines pinned separately

Nothing exists yet. The first decision that is expensive to reverse is how the dependencies are
named: research §1.1 found that "the stack version" does not exist — kompot is one line taken
through a platform, petich another, booblik a third, and katcher runs **three** at once (server
`0.6.2`, `client` `0.5.1`, `client-android` and the Gradle plugin `0.4.92`). Java 25 is mandatory for
every consumer of kompot or petich, not a preference.

- **The decision and its reason.** kompot is declared once, as `platform("io.github.youndie:kompot-bom")`,
  and no kompot coordinate carries a version anywhere in the build. A version tail is the CI run
  number, so `kompot-core:0.30.0.71` beside `kompot-client:0.30.0.72` is a combination nobody built
  and is trivially easy to write by hand. Through the platform it cannot be written down.
- The rejected alternative is one `stack` version property in the catalogue. It reads well and
  resolves nothing: the first `katcher` coordinate would fail with a message naming the artefact
  rather than the mistake.
- Not covered: publishing anything. konekt consumes, it does not publish.

- AC: `./gradlew dependencies` shows every kompot coordinate at one version and no version literal
  for any of them in the build files.
- AC: the version catalogue has separate, commented entries for `katcher-server`, `katcher-client` and
  `katcher-android`, and a comment saying why they are three.
- Anchors: `gradle/libs.versions.toml`, `settings.gradle.kts`, `build.gradle.kts`,
  `buildSrc/src/main/kotlin/`.

Background: [research-architecture](../research/research-architecture.md) §1.1.
