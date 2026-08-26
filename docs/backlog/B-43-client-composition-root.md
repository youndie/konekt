---
id: B-43
title: "The client has every part of an application and no application"
status: open
priority: P1
size: L
stage: stage-m3-product
epic: feature-client
---

# B-43 — The client has every part of an application and no application

`:client` holds a session that refreshes on a 401, an HTTP client behind ktor's bearer plugin, an SSE
source that reconnects and announces its gaps, a component registry with three renderers and a
degradation block, a design system with two brands and a shape scale. Every one of them is tested.

**Nothing calls any of them.** There is no `App` composable, no screen holder, no entry point on any
platform and no Xcode project. `grep -rn 'KonektTheme|konektRegistry'` over the whole repository
returns the declarations, the documents, and test fixtures — and no application code at all.

This is not a gap in a feature. It is a gap in the backlog: four items are stopped on it and none of
them names it, so each reads as a separate difficulty when they are one.

| Item | What it cannot do without a root |
|---|---|
| [B-22](B-22-brand-b.md) | the brand kit is served and nothing fetches it, so "switching the served theme repaints the application" has no application to repaint |
| [B-27](B-27-ios-crash-gap.md) | "a deliberate crash in the iOS build" needs an iOS build to crash; the reporter is wired into a library |
| [B-28](B-28-screenshot-tests.md) | its goldens photograph fixtures assembled in the test, not what a subscriber sees |
| [B-18](B-18-cache-versus-realtime.md) | both halves of its decision — re-ask on a changed revalidation, clear the overlay on `streamRestarted` — are the screen holder's behaviour, and there is no holder |

- **The decision and its reason.** The root is a **screen holder**: it fetches a component tree by
  address, renders it through the registry, provides the theme and the update overlay around it, and
  owns the session. It is not a navigation graph — `kompot-navigation` exists and this build does not
  use it (research §1.11) — so "which screen" is a value the holder is given, not a framework.
- **Desktop first, and that is a choice about verification rather than about the product.** The JVM
  target already renders through Skiko in tests, viddik photographs it, and `runComposeUiTest` drives
  it — so a desktop entry point is the cheapest place where "the application draws what the server
  sent" becomes an assertion instead of a screenshot somebody looks at. iOS follows, and it needs an
  Xcode project, which is the part with no Kotlin in it.
- The rejected alternative is going straight to iOS because three of the four waiting items mention
  it. It buys the same root plus a toolchain nobody here has exercised, and the first failure would be
  indistinguishable between the two.
- **The overlay and the re-ask belong here and nowhere else.** B-18 established both by reading the
  toolkit: `KompotRealtimeProvider` keys its map by topic and konekt never changes the topic, so
  nothing clears a stale update; and `CachedKompotScreenProvider.getScreen` answers once and its
  background revalidation reaches nobody. Both corrections are two lines in a holder that exists.
- Not covered: Android. It needs an `.aar` and a second entry point, and no item is waiting on it —
  the moment one is, `:client` gains the target rather than this item growing.
- Not covered: a real navigation stack. One screen at a time, with the address as a parameter, until
  a second screen makes the question concrete.

- AC: `./gradlew :client:run` opens a window against the stand, signs in, and draws the home screen
  the server built — including a counter card whose text the client did not compose.
- AC: an unknown component in that tree draws the degradation block rather than nothing, in the
  running application rather than in a fixture. `B-25` supplies the route that sends one.
- AC: `streamRestarted` clears the update overlay before the refetch, and a test drives the sequence
  — stale overlay, gap, refetch — and asserts the screen shows the refetched value. That is the
  unbounded failure B-18 named and the only one of its findings that cannot be worked around.
- AC: the goldens of `B-28` photograph a tree the SERVER produced, from a recorded response, rather
  than one the test assembled.
- Anchors: `client/src/commonMain/kotlin/io/konekt/client/app/`,
  `client/src/jvmMain/kotlin/io/konekt/client/Main.kt`.

Background: [B-18](B-18-cache-versus-realtime.md) for the two corrections the holder owes,
[research-architecture](../research/research-architecture.md) §1.11 for why there is no navigation
graph and §1.14 for why the client reaches two iOS targets rather than three.
