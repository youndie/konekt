---
id: B-103
title: "The travel screen shows what you own and offers no way to buy, so travel packages read as unavailable"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-103 — The screen for travel packages is the one place you cannot get one

A subscriber went looking for a travel package and reported they were **not available**. They are:

```
GET /api/v1/screens/plans   →   Home $15 · Turkey $12 · Europe $9  (all pressable)
                                United States $24  (sold_out)
```

But the screen called **Travel packages** answers this, and nothing else:

```
Travel packages
[banner] No travel package on this line yet.   → See plans
```

It lists what the line already holds. With nothing held it shows one banner, and the way out is the
general catalogue where travel sits mixed in with the home bundle. So the screen named after the
thing is the screen that does not sell it, and a subscriber who opens it concludes there is nothing
to buy.

**This is a gap in the flow rather than a defect in a route.** [B-88](B-88-roaming-starts-through-a-dev-route.md)
gave roaming a screen and deliberately did not give it a catalogue; that was the right scope then and
it is what a person meets now.

## The decision

- **The travel screen offers the travel packages**, filtered from the same catalogue the plans screen
  reads — not a second catalogue, and not a second price list. One `PlanCatalog`, one set of prices,
  and the filter is the zone the plan carries.
- **A plan already owned is still shown as owned.** The screen's present content does not move; the
  offer goes below it, so the question it answers first is still *what do I have*.
- **The `sold_out` state is honoured rather than hidden.** The United States plan is sold out today,
  and a catalogue that silently omits it teaches a subscriber that the list is what exists.
- **Rejected: making the home banner smarter.** The door works; what is missing is what lies behind
  it.

## Acceptance criteria

- AC: from the Travel screen, with no package on the line, a subscriber can buy one without visiting
  the general catalogue — driven end to end in the stand suite, not asserted on a tree alone.
- AC: the plans screen keeps offering travel packages too. Two doors to one purchase is fine; one
  door that leads nowhere is what this fixes.
- AC: the empty state still says something rather than drawing an empty column — the rule the home
  screen and the catalogue both follow.
- AC: prices come from the one catalogue, verified by a test that would fail if a second list
  appeared.

## Anchors

| What | Where |
|---|---|
| The screen | `server/src/main/kotlin/io/konekt/roaming/RoamingScreen.kt`, `RoamingUseCases.kt` |
| The catalogue and the zone each plan carries | `feature/purchase-server-data/.../StaticPlanCatalog.kt` |
| The general catalogue that does offer them | `server/src/main/kotlin/io/konekt/screens/PlansScreen.kt` |
| The item that scoped the screen without one | `docs/backlog/B-88-roaming-starts-through-a-dev-route.md` |

## What was done

The travel screen carries the offer under what is held:

```
Travel packages
[banner] No travel package on this line yet.
Packages for your next trip
  Turkey        $12   pressable
  Europe        $9    pressable
  United States $24   sold_out, not pressable
```

**The card comes from the catalogue's own builder.** `PlansScreen.card` became `internal` and the
travel screen calls it — one plan, one price, one badge, one idea of what sold out looks like. A
second builder here is how a plan acquires two prices the first time either is edited, which is the
defect this repository has already met on the eSIM count and on the pending-change sentence.

**The empty banner lost its "See plans" control.** It pointed at another screen because the offer
lived there; with the offer on this screen a control pointing away from it would be a door out of the
room a subscriber has just been let into.

**Sold out is offered rather than hidden**, the same rule the catalogue follows. `us-20gb-30d` is in
the list, marked, unpressable — a list that silently omits what it will not sell teaches a subscriber
that the list is what exists, and the refusal path needs a fixture somebody can find.

## Where this came from, which is worth recording

The door to this screen was opened **in the same session that found it empty**. `B-88` made the home
banner conditional on already owning a package, so the empty state was unreachable;
`EveryScreenIsReachableTest` said *reachable from nowhere*, the banner was made unconditional — and
what that opened was a room with nothing in it. A reachability guard proves there is a door, not that
there is anything behind it.

## Verified

- Three new assertions on the view: the filter is by zone and excludes the home bundle; a plan not on
  sale is still shown; the offer does not vanish when something is held. Nine tests in that file, all
  green.
- Driven against a running stand with a brand new subscriber — the output above is that response,
  not a rendering of the intent.
- `:server:test` green; `make e2e` green.
- `KoinGraphTest` gained `PlanCatalog`, which is the honest consequence of the composition root
  reaching one module further: a route is not verified, a `factory` is.

## Anchors

| What | Where |
|---|---|
| The offer on the view | `server/src/main/kotlin/io/konekt/roaming/RoamingUseCases.kt` |
| The screen | `server/src/main/kotlin/io/konekt/roaming/RoamingScreen.kt` |
| The one card builder | `server/src/main/kotlin/io/konekt/screens/PlansScreen.kt` (`internal fun card`) |
| The tests | `server/src/test/kotlin/io/konekt/roaming/ViewRoamingUseCaseTest.kt` |
