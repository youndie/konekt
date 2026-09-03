---
id: B-121
title: "Nothing asserts that the Android build resolves android variants, and the one defect that ever mattered here was a silent substitution"
status: open
priority: P3
size: S
stage: stage-m7-completeness
---

# B-121 — The Android classpath is checked by hand, once, and never again

`B-85` verified by reading a dependency report that `kompot-client-android` — not the desktop or JVM
variant — is what an Android compilation resolves. That verification was correct, it was worth doing,
and it happened exactly once. Nothing repeats it.

**The failure it guards against does not fail the build.** Gradle picks the best matching variant, so
a toolkit that stops publishing an android one is not a resolution error: the consumer quietly gets
the JVM variant and compiles. That is not a hypothesis — it is what
[katcher#27](https://github.com/youndie/katcher/issues/27) was. The build was green, the app ran, and
the only symptom was a crash report that never arrived, found on a device after
`System.getProperty("user.dir")` turned out to be `/`.

iOS does not have this problem: there is no jvm variant for a native compilation to fall back to, so a
missing Apple target fails loudly at resolution. **Android is the platform where absence is silent**,
and it is now the platform where the fallback is most plausible, because
`ru.workinprogress.katcher:client-android` changed meaning in `0.6.41` — it used to be a separate
single-platform library and is now the android variant of the multiplatform one.

Re-checked by hand on 2026-09-04 from the `.module` files in the Gradle cache: at `kompot
0.36.1.112`, `katcher 0.6.41` and `tracy 0.1.13` every coordinate this client compiles against still
publishes an android variant, and `research-architecture` §1.9 records it. That is the state today and
says nothing about the next bump.

## What it takes

A test in `:client`, or a small Gradle task, that resolves `androidRuntimeClasspath` and asserts that
each named coordinate arrives with `org.jetbrains.kotlin.platform.type = androidJvm` — the same
question `./gradlew :client:dependencyInsight` answers by hand. The list is short and is the one
`B-85` checked: kompot's client modules, katcher's `client`, tracy's `agent`.

The alternative shape — reading the published `.module` metadata — is weaker: it proves a variant was
published, not that this build resolved it, and the defect is about what arrives on the classpath.

## Acceptance criteria

- AC: a check fails when a dependency on the Android classpath resolves to a non-android variant.
- AC: it is proved by mutation — forcing one coordinate to its JVM variant makes it fail.
- AC: it runs where the Android build already runs, so it costs no new job.

## Anchors

| What | Where |
|---|---|
| The one-time verification | `B-85`, AC3 |
| What silent substitution cost | `androidApp/src/main/kotlin/io/konekt/android/CrashActivity.kt`, katcher#27 |
| Today's re-check | `docs/research/research-architecture.md` §1.9 |
| The classpath | `client/build.gradle.kts` |
