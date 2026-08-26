---
id: konekt-client
title: konekt client (Compose Multiplatform)
type: service
status: active
repo_url: https://github.com/youndie/konekt
module: client
tech_stack: [Kotlin Multiplatform, Compose Multiplatform 1.11.1, kompot client, Ktor client CIO on JVM and Darwin on iOS, JVM + iosArm64 + iosSimulatorArm64]
owner: unassigned
depends_on:
  - konekt-server
publishes:
  - nothing; it is built and run, not published
---

# konekt client

> The renderer half of a backend-driven product: it owns no screen layout. Everything below was read
> out of `client/build.gradle.kts` and the files in §2a on 2026-08-25.

## 1. Responsibility

It holds the four things a backend-driven client is allowed to own:

1. **The renderer registry** — which wire type draws as what
   (`client/src/commonMain/kotlin/io/konekt/client/render/KonektRenderers.kt`);
2. **The session** — where the tokens live and how they are refreshed
   (`client/src/commonMain/kotlin/io/konekt/client/session/KonektSession.kt`);
3. **The transport** — the HTTP client and the SSE stream
   (`client/src/commonMain/kotlin/io/konekt/client/net/KonektHttpClient.kt`,
   `client/src/commonMain/kotlin/io/konekt/client/realtime/SseRealtimeSource.kt`);
4. **Shape** — the radii and the surfaces, because the wire has no vocabulary for them
   (`client/src/commonMain/kotlin/io/konekt/client/theme/KonektDesignSystem.kt`).

What it does not own: **layout, copy, money formatting and the meaning of a state word.** All four
come from the server. A counter is "low" because the server said so, and `$12` is a string the server
built.

**There is no navigation graph, no view model and no screen class in this module today** — the
screens in [`docs/screens/`](../screens/) are server-built trees, and this module renders whatever
arrives. That is stated rather than implied, because it is the first thing a reader looks for.

## 2. API contracts

The same `@Resource` classes the server uses, through `ktor-client-resources`: the module depends on
`:feature:auth-shared-api`, `:feature:realtime-shared-api`, `:feature:usage-shared-api` and
`:feature:esim-shared-api` so that a path is never written as a string here either.

## 2a. Code anchors

| File | What is there |
|---|---|
| `client/build.gradle.kts` | targets, the pinned Compose versions, and why they are pinned |
| `client/src/commonMain/kotlin/io/konekt/client/net/KonektHttpClient.kt` | the bearer plugin, the refresh call, the circuit breaker |
| `client/src/commonMain/kotlin/io/konekt/client/session/KonektSession.kt` | the token store and the single-writer mutex |
| `client/src/commonMain/kotlin/io/konekt/client/realtime/SseRealtimeSource.kt` | the stream, its backoff, and the restart signal |
| `client/src/commonMain/kotlin/io/konekt/client/render/KonektRenderers.kt` | the registry: kompot's renderers plus konekt's three |
| `client/src/commonMain/kotlin/io/konekt/client/render/UnknownBlockRenderer.kt` | what a component this build does not know draws |
| `client/src/commonMain/kotlin/io/konekt/client/theme/KonektDesignSystem.kt` | shape, and surviving a server theme |

## 3. How it is built

**JVM and two iOS targets, and the module names its own rather than using the `konekt.multiplatform`
convention plugin.** It was JVM only, and that was upstream rather than a choice: kompot's Compose half
published `-android`, `-desktop` and `-wasm-js` and no iOS artefact while the protocol half published
the three iOS targets. [kompot#84](https://github.com/youndie/kompot/issues/84) closed it in
`0.31.0.76`.

Two targets and not three, and the reason changed completely while the conclusion did not: Compose
stopped publishing `iosX64` after `1.11.0-alpha01`, so `iosArm64` and `iosSimulatorArm64` are what
exist. Verified in the module metadata of `kompot-client`, `kompot-theme-client` and
`kompot-ds-material-compose` rather than read off the issue.

**And it runs there.** `scripts/ios-home-app.sh` assembles a simulator `.app` from a Kotlin/Native
executable — no Xcode project, because what an iOS application needs is a `UIApplicationMain`, a
delegate that owns a window and a root view controller, and `platform.UIKit` has all three. The
application that draws is built by the same compiler, from the same source set, as everything in it.

**Compose versions are matched to the toolkit's binaries**, not to the newest release: `1.11.1` with
material3 `1.11.0-alpha07`, named by coordinate. A newer foundation beside the toolkit's material3
resolves, compiles, and then throws `AbstractMethodError` inside a renderer.

**The session lives behind ktor's bearer plugin, not an interceptor.** The plugin already knows when
to attach a token and when to ask for a new one; what it lacks is somewhere to keep them.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Service | [konekt-server](konekt-server.md) | every screen, every action, the stream |
| Library | kompot (BOM) | the wire, the renderers, the theme and the realtime frame contract |
| Library | `qrcode` | a QR **matrix**, not a widget — the drawing stays ours |

## 5. Infrastructure and deploy

Not deployed. It is compiled by the ordinary build, and its tests run on the JVM.

## 6. Local setup

```bash
~/.claude/bin/wsl-run ./gradlew :client:jvmTest
```

## 7. Configuration

None. The base URL is a constructor parameter of `konektHttpClient`, not an environment variable.

## 8. Quirks

- **A stored session IS attached to the very first request.** The "first request goes out bare and
  comes back 401" shape belongs to a session with no tokens yet — `SessionRefreshTest` asserts the
  first case explicitly, because the second one was believed and is false.
- **`markAsRefreshTokenRequest()` does not exist in Ktor 3.5.** Without
  `attributes.put(AuthCircuitBreaker, Unit)` the refresh call re-enters the same plugin carrying the
  token that just failed. Measured, not assumed: the request arrived at the refresh endpoint with
  `Bearer stale` on it. The endpoint is public and ignores it, so nothing breaks **today**.
- **A failed refresh clears the session rather than retrying.** The server rotates refresh tokens and
  detects reuse, so a second attempt with the same token ends the family: retrying is the one
  response guaranteed to make things worse.
- **MockEngine and the SSE plugin do not meet.** No frame ever arrives and the collector waits, so the
  failure is a timeout naming the test rather than the cause. Stream tests run an embedded CIO server
  on an ephemeral port.
- **`Last-Event-ID` is deliberately unused.** It resumes by replaying, which needs a server that
  numbers and keeps frames; this one does neither, because an update is losable by design. The client
  announces the gap through `streamRestarted` and the screen refetches.
- **konekt replaces the toolkit's `UnknownComponent` renderer.** The default reports and draws
  nothing, and a hole is indistinguishable from a screen that failed to load. The wire name goes to
  the degradation sink where an operator can count it; on the screen it is a word nobody can act on.
- **`SseRealtimeSource.subscribe(topic)` ignores its argument.** The stream carries one subscriber's
  updates and the subscriber comes from the verified token — a stream addressed by a parameter is
  every subscriber's screen for anybody who asks. The parameter stays because it is the toolkit's
  contract.
