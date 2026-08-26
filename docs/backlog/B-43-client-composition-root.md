---
id: B-43
title: "The client has every part of an application and no application"
status: wip
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

## What landed: the holder, and the one correction only it could make

`KonektApp` is a screen holder — fetch one tree by address, render it through the registry, provide
the theme and the update overlay around it. What it takes is a `ScreenSource`: four operations the
holder performs on the outside world, not a repository abstraction hiding HTTP.

**B-18's unbounded failure is closed.** An update recorded before a stream gap kept shadowing the
correct component of a screen fetched after it, for the life of the composition, with a healthy
network and no error anywhere — the one finding of that item that could not be worked around, and it
needed a holder that did not exist. The holder clears the overlay on `streamRestarted` and then
refetches, in that order.

The order is the correctness, and both halves are proved separately by mutation:

| Removed | What the test says |
|---|---|
| the clear | the screen keeps showing the pre-gap value forever |
| the refetch | the screen keeps showing the pre-gap tree |

Each mutation fails the same test, which is the point: neither half is worth anything alone. There is
a positive control beside it — the first fetch draws what the server sent — because without it "the
refetched value is shown" is satisfied by a holder that draws nothing until a restart.

**Its own map, not `KompotRealtimeProvider`'s.** That composable holds its `SnapshotStateMap`
privately and empties it only when the topic changes, which konekt never does: one topic per
subscriber. So the map has to be ours for the clear to be possible at all. Reported upstream rather
than forked, per D9.

## The runner, and the wiring under it

`KonektScreenSource` is the real source behind the holder — four operations over the client this
module already builds — and `:client:run` exists: a `compose.desktop.application` block and a
`Main.kt` that signs in against the stand and opens a window on the home screen.

**It is a runner and not the product, and the difference is one route.** It signs in through
`/api/v1/dev/otp`, which reads back a one-time code and exists only where `DEV_REVEAL_OTP` is set. A
machine endpoint revealing any subscriber's code IS the authentication system; a real client draws a
login screen and the subscriber types what they were sent.

`KonektScreenSourceTest` closes the write-and-never-call gap the interface would otherwise have left:
an embedded server sends a real encoded tree, the source decodes it, the holder draws it, and the
assertion is on text the SERVER composed — the client has no formatter for money or for gigabytes,
deliberately (D15), so "9.7 GB left" on screen can only have been given to it. Proved by mangling the
component's wire name in the response and watching it fail.

Two things the harness taught, both worth keeping:

- `waitForIdle` returns while the fetch is still in flight. Idleness is about composition, not about
  somebody else's suspending call, so the assertion needs `waitUntil`. The first failure here was the
  harness — the fetch was already correct, which a separate direct call proved before anything was
  changed.
- an embedded server rather than `MockEngine`, for the reason the SSE tests in this module already
  record: the two never meet, and the collector simply waits.

## What is left, and it is one thing

- **AC 1 is met in substance and not in form.** `:client:standTest` drives the real holder, the real
  source and the real registry against the running stand, and asserts the home screen draws `$0` —
  which the SERVER composed, because the client owns no formatter for money (D15). What nobody has
  done is watch the window: WSL has no display, so `:client:run` is compiled and never launched. That
  is exactly the claim this repository refuses to make on a green compile.
- **AC 2 is met**, through the same suite: the development screen from `B-25` renders and both
  unknown components draw the block with their neighbours intact. Asserted as TWO blocks rather than
  "at least one", because one block plus one silently dropped component is the failure that screen
  exists to make visible.
- **AC 4 is met.** `Screen - Recorded home` is a golden of a tree decoded from a response recorded
  off the running stand — sign in, top up, buy the home plan, confirm, read `/api/v1/screens/home` —
  committed verbatim and decoded with the CLIENT's own `Json`, so a type this build cannot draw shows
  the degradation block rather than a fixture's idea of the screen.

  Every other golden in that package photographs values assembled in the test, which is right for what
  they are for and means none of them can fail when the SERVER stops sending what they draw. This one
  can. `RecordedScreenIsRealTest` is what keeps it honest: a recording that decoded into two
  degradation blocks would draw a perfectly good picture and be filed as the home screen, so the
  assertion is that NOTHING in it is unknown, and that it carries text only the server could have
  composed — a formatted amount and a formatted allowance, neither of which the client can produce
  (D15). Mutation-proved by renaming a wire type in the recording.

  Refreshing it is deliberate work rather than a build step: a recording that regenerated itself would
  agree with whatever the server does today, and agreeing with today is what a golden must not do.
- iOS remains after the desktop one, and it is the part with no Kotlin in it — an Xcode project.

## Two findings the composition root surfaced, which is what it was for

**A client cannot learn its own subscriber id.** `UpdateSessionAction` carries an access token and a
refresh token and nothing else, which is why the e2e stand reads the id out of the database. Nothing
breaks today because the realtime topic turns out not to need it — see below — but any screen
addressed by subscriber would.

**The realtime topic is a local key, not an address.** `SseRealtimeSource.subscribe` ignores the topic
it is given: the path is fixed and the SERVER derives the topic from the caller's token. So the
`topic` parameter keys the overlay map and nothing else. That is a sound design — the client cannot
subscribe to somebody else's stream even by mistake — and it is invisible from either side alone,
which is why it took a composition root to notice.

## The stand suite is a task of its own, for the reason `:e2e` already had

`:client:standTest` needs a deployment that is already up. Wired into `check` it would fail every
ordinary build on a machine that has not started one, and a suite that fails for reasons unrelated to
the change is a suite people mute. So `jvmTest` excludes the package and `make e2e` runs both.

That exclusion taught the unrun-test guard something: an EXCLUDE is a filter too. Without it the check
demanded that `jvmTest` report a class it had been told not to run — the same false positive `--tests`
produced, arriving from the other direction.

**Two assertions were wrong the first time, and both were wrong about the product rather than about
the code.** The balance renders as `$0` and not `0.00`; and the degradation block deliberately does
NOT put `originalType` on screen — the canvas's copy says what to do instead of what is missing, and
the wire name is for the sink an operator reads. Both were fixed by looking at what the stand actually
serves and at what the renderer actually draws.
