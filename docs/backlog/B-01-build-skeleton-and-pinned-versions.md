---
id: B-01
title: "Gradle skeleton: 9.7.1 on Java 25, convention plugins, ktlint, and six dependency lines pinned separately"
status: open
priority: P0
size: M
stage: stage-m0-wire
---

# B-01 — Gradle skeleton: 9.7.1 on Java 25, convention plugins, ktlint, and six dependency lines pinned separately

Nothing exists yet, and the first decision that is expensive to reverse is how the dependencies are
named. [research-architecture](../research/research-architecture.md) §1.1 found that "the stack
version" does not exist — kompot is one line taken through a platform, petich another, booblik a
third, and katcher runs **three** at once (server `0.6.2`, `client` `0.5.1`, `client-android` and the
Gradle plugin `0.4.92`). Java 25 is mandatory for every consumer of kompot or petich, not a taste.
[research-stack](../research/research-stack.md) §1.1 carries the whole version table, read from
`repo1.maven.org` rather than recalled.

- **The decision and its reason.** kompot is declared once, as
  `platform("io.github.youndie:kompot-bom")`, and no kompot coordinate carries a version anywhere. A
  version tail is the CI run number, so `kompot-core:0.30.0.71` beside `kompot-client:0.30.0.72` is a
  combination nobody built and is trivially easy to write by hand. Through the platform it cannot be
  written down.
- **`group`, `version` and `jvmToolchain(25)` live in a convention plugin in `build-logic`, not in
  each module.** Repeating a coordinate per module is how six modules get published under a group
  derived from the root project name; here nothing is published, but the same repetition is how one
  module gets left on 21 and fails with a message naming the dependency rather than itself.
- ktlint is applied to every subproject from the root, and the **CLI** version is pinned explicitly
  from the catalogue rather than left to the plugin's default — otherwise the style shifts on a plugin
  bump, which is the moment nobody reads the diff. The bare version string carries
  `# renovate: datasource=maven depName=com.pinterest.ktlint:ktlint-cli` above it, because nothing
  resolves it and therefore nothing updates it. An `.editorconfig` sits beside it; ktlint reads it.
- The rejected alternative is one `stack` version property in the catalogue. It reads well and
  resolves nothing: the first `katcher` coordinate fails with a message naming the artefact rather
  than the mistake.
- Not covered: publishing. konekt consumes and publishes nothing, so no `maven-publish`, no api dump.
- Settles [research-stack](../research/research-stack.md) open question 5 — whether this repository
  joins the mutagen set, and with which task set staying on the Mac.

- AC: `./gradlew dependencies` shows every kompot coordinate at one version, and no version literal
  for any of them exists in any build file.
- AC: the catalogue has separate, commented entries for `katcher-server`, `katcher-client` and
  `katcher-android`, and a comment saying why they are three.
- AC: `./gradlew ktlintCheck` runs in every module from one root declaration, and formatting is
  identical before and after a plugin-only bump.
- Anchors: `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`,
  `settings.gradle.kts`, `build-logic/src/main/kotlin/`, `.editorconfig`.

Background: [research-architecture](../research/research-architecture.md) §1.1,
[research-stack](../research/research-stack.md) §1.1, D11, D12.
