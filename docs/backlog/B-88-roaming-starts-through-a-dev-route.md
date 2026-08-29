---
id: B-88
title: "Roaming has no screen of its own, and the only way to start a package is a public dev route that names the subscriber in a query"
status: done
priority: P1
size: M
stage: stage-m7-completeness
---

# B-88 — The state the feature exists for is reachable only by a route that must never ship

[B-19](B-19-roaming.md) built the good half: a package bought at home lies dormant, activates on
first use abroad, and is dated from that moment rather than from the purchase. That is a real
telecom rule and it is implemented.

What is missing is everything around it:

- **No roaming screen.** Packages appear as cards on home; there is no zone list, no country search,
  no place that answers "what do I have for this trip".
- **`feature/roaming` has no `-shared-api` module**, so nothing about roaming is on the wire as a
  contract the client knows — the vertical is server-only.
- **The client does not know `dormant`.** `UsageCounterCardRenderer` draws it as an ordinary card,
  which is correct degradation and the wrong colour for the one state this feature was built to
  show.
- **Activation is a dev route.** `POST /api/v1/dev/roaming/arrive` is public, gated by `DEV_SCREENS`,
  and takes `subscriberId` from the **query rather than the token** — so where it is enabled, anyone
  can start a stranger's package and begin spending it.

For a reference build the last point is the sharpest: the demonstration of a dormant package
becoming active — the whole feature — runs through the one route that is documented as never
shippable.

- **The decision: give roaming a screen, put `dormant` in the client's vocabulary, and move arrival
  onto the simulator.** The traffic chain already publishes zoned events for trips under way
  (`TrafficSimulator`'s `travelling`); what it cannot do is *begin* one. A simulated arrival is the
  same kind of fiction as simulated traffic and belongs beside it, behind `SIMULATE_TRAFFIC`, taking
  the subscriber from the same place the rest of the chain does.
- **The rejected alternative is to authenticate the dev route.** That makes it safe and leaves the
  demonstration depending on a route the deployment must not enable, which is the actual problem.
- **The rejected alternative is a screen with no arrival.** A roaming screen whose packages never
  start is a list of rows in one state.
- This item does **not** observe a real network attachment — [B-19](B-19-roaming.md) recorded that
  as out of scope and it stays out; nothing here watches a device land anywhere.

- AC: a roaming destination is in `Shell.graph()` and shows the subscriber's packages by zone, with
  dormant, active and expired distinguished on screen.
- AC: a dormant package looks dormant in the client — the state has a renderer branch and a
  screenshot case, not a fallback.
- AC: `/api/v1/dev/roaming/arrive` is deleted, and the scenario that used it drives arrival through
  the simulated chain instead.
- AC: `RoamingScenarioTest` passes against the new path.
- Anchors: `server/src/main/kotlin/io/konekt/roaming/`,
  `server/src/main/kotlin/io/konekt/roaming/dev/ArriveRouting.kt`,
  `feature/roaming-server-domain/src/main/kotlin/io/konekt/feature/roaming/server/domain/RoamingDomain.kt`,
  `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficChain.kt`,
  `client/src/commonMain/kotlin/io/konekt/client/render/UsageCounterCardRenderer.kt`.

## What was done

**Arrival left the route table.** `POST /api/v1/dev/roaming/arrive` is deleted — file, resource,
endpoint facts, the entry in `devScreensRouteGroup` and the line in the guard's expected set. What
replaces it is a **delay**: a package lies dormant for `SIMULATED_ARRIVAL_AFTER_SECONDS` and then the
traffic simulator publishes one megabyte in its zone, through the same broker every other event takes.

The delay is the design, not a detail. The route existed because the simulator deliberately would not
start a package — one that started itself five seconds after purchase makes the state this feature is
about, *bought and not counting*, unobservable. A cutoff answers both halves: the package stays
dormant long enough to be looked at, and nothing outside the process decides when it stops.

Ninety seconds by default, ten on the compose stand. The two numbers want opposite things — a
demonstration needs long enough to show the card and talk about it, an end-to-end scenario needs short
enough not to sleep for a minute and a half — which is why it is configuration rather than a constant.

**Proved by mutation**: taking the delay out of the cutoff makes both packages in
`TrafficChainTest`'s new case depart on the first tick, which is exactly the behaviour the deleted
route existed to avoid.

**Roaming is on the wire.** A new `feature:roaming-shared-api` — one address and **no action**, and
the absence is the feature: a package is started by using it, and a `StartRoamingAction` would be the
deleted route moved into the client.

**The screen.** `GET /api/v1/screens/roaming`, grouped by zone, ordered by what is counting now, then
what is waiting, then what has ended — a subscriber's order of attention. Reached from the home
screen, where the cards are, and only when there is something to look at. The cards stay on home
besides: a package bought for a trip must be visible on the screen somebody opens, not only behind a
link.

**`dormant` reached the client.** `UsageCounterCardRenderer` had no branch for it — the server has
sent the word since `B-19` and it fell through to the ordinary card. It now draws in
`onSurfaceVariant`: the bar is full and will still be full in a month, so the accent role says
"running, plenty left", which is the one thing a dormant package is not.

`onSurfaceVariant` and **not** `outline`, though outline is quieter — outline is the role for
BORDERS, and this colour draws a number somebody has to read. Reaching for a token because its default
value looks right is how `sold_out` came to label a subscriber's own tariff in
[B-86](B-86-changing-tariff-has-no-screen.md), two items ago.

### A stale guard, found on the way

`ScreenshotCasesTest` keeps a negative fixture — a counter state this build genuinely does not know —
and checked it against **three names retyped by hand**. `DORMANT` had been in the vocabulary since
`B-19` and was not among them, so the guard would have passed on a fixture naming a state the build
knows perfectly well, which is the opposite of what it asserts. `CounterStates.all` now exists beside
the constants and the guard asks for it.

## Verified

- `TrafficChainTest` — the arrival case, driven through the real broker, asserting both sides of the
  delay and that an arrival costs one megabyte. Mutation-proved.
- `Counter - Dormant` is a recorded screenshot case; the six home and gallery goldens moved with the
  colour and the diff was **looked at** rather than accepted — only the Turkey card changed.
- `RoamingScenarioTest` waits for the simulation instead of calling a route, and asserts the package
  was dormant *first*, so a simulator that started everything on tick one could not satisfy it. 34
  e2e tests, 0 failures.
- `./gradlew check` green; `make check` green.

### The door was conditional, and the guard caught it after the merge

The banner leading to the travel screen was drawn only when the subscriber already had a package. So
the screen's EMPTY state — *no travel package on this line yet*, with the way to the catalogue — could
be reached by nobody: the only door to it closed exactly when it was the state you would see.

`EveryScreenIsReachableTest` said so in one line — *reachable from nowhere and not declared:
app://roaming* — and it was right about the product rather than only about the graph. The banner is
unconditional now; the cards stay conditional, because a subscriber with no packages needs no empty
list on the home screen and that is what the screen behind the banner is for.

**It was caught by CI and not by me**, and the reason is worth writing down: `make e2e` runs
`:e2e:e2e` **and** `:client:standTest`, and I had been running the first task directly. The target was
right and the habit was wrong.

## What is deliberately not in scope

Observing a real network attachment — [B-19](B-19-roaming.md) recorded it as out of scope and it
stays out; nothing here watches a device land anywhere. Per-zone pricing, zone discovery, and a screen
listing which countries a zone contains are still out too.

## Anchors

| What | Where |
|---|---|
| The screen | `server/src/main/kotlin/io/konekt/roaming/RoamingScreen.kt`, `RoamingRouting.kt` |
| Its address | `feature/roaming-shared-api/.../RoamingApi.kt` |
| Arrival, now a delay | `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficSimulator.kt`, `TrafficChain.kt` |
| The query behind it | `feature/roaming-server-domain/.../RoamingDomain.kt` (`awaitingArrival`), `feature/roaming-server-data/.../ExposedRoamingPackages.kt` |
| The dormant branch | `client/src/commonMain/kotlin/io/konekt/client/render/UsageCounterCardRenderer.kt` |
| The route that is gone | `server/src/test/kotlin/io/konekt/DevRoutesAreNotProductionTest.kt` |
