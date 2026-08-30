---
id: B-96
title: "Screens are drawn from repositories, so every presentation decision is testable only through a tree"
status: done
priority: P2
size: L
stage: stage-m7-completeness
---

# B-96 — Name the layer half the server already has, and finish it

The server draws the screens. That is the claim kompot exists to demonstrate here, and this build
demonstrates it twice, in two different shapes, and says nowhere which one it means.

**One shape**, in four verticals: a use case returns a view, a screen renders it.

| View | Returned by | Rendered by |
|---|---|---|
| `TopUpView` | `TopUpUseCases` | `TopUpScreens.result` |
| `TariffChangeView` | `ViewTariffChangeUseCase` | `TariffChangeScreen.build` |
| `EsimWizardView` | `EsimWizardUseCases` | `EsimWizardScreen.build` |
| `OrderView` | `PurchaseUseCases` | `PurchaseResultScreen` |

**The other shape**, everywhere else: the route reads repositories and hands them to the screen.

```kotlin
HomeScreen.build(msisdn, balance, counters, cards, packages, roamingCards, brandName, esims, nav)
```

Six values out of four repositories and two injected card factories, and the screen decides what to
draw from them. `ProfileRouting` goes one step further and composes a sentence of product copy —
*"A change to $to is waiting for your confirmation, and takes effect on …"* — inside the routing
file, which is the layer that is supposed to know only who is calling.

And the first shape does not finish the job either: `TariffChangeScreen.build(view, tariffs)` takes
the view **and the catalogue**, because the view carries tariff ids and the screen has to resolve
them into names. A view that still needs a lookup at render time is a view that has not decided
anything yet.

## What this costs, concretely

**Every decision is asserted through the tree.** `HomeScreenTest` answers "is the install door open
for a line that holds a profile it has not installed" like this:

```kotlin
screen.konektWalk().filterIsInstance<BannerComponent>().singleOrNull { it.id == "home-install-esim" }
```

The question is a boolean about a subscriber's state. The assertion is a graph walk keyed on a string
id. When that id changes, the test does not fail — it stops finding anything and quietly asserts
about `null`, which is the same shape of vacuity `B-84` found four times over in one file.

**A reader of the reference learns two patterns and cannot tell which is meant.** That is the gap of
a *reference*, which is what this stage is for: not a missing feature, a missing rule.

## The decision

**`data → useCase → view → render`, and the render step is total: it takes exactly one view and looks
nothing up.**

- **A view is complete.** No repository, no catalogue, no card factory travels beside it. If the
  screen needs a tariff's title, the title is in the view — not the id.
- **The view holds decisions and values; the renderer holds components, ids, styles and copy.**
  Whether the install door is drawn is a view field. What the banner says, and that it is a
  `BannerComponent` with `MessageTones.INFO`, is the renderer's.
- **Formatting stays in the renderer.** `MoneyFormat` and `DayFormat` are rendering, and a view that
  carries `Money` rather than `"$38"` stays comparable in a test. This is what `TopUpView` already
  does and it is right.
- **The view is server-internal and never enters a `*-shared-api` module.** The wire is the component
  tree. A view type on the wire would make the client depend on how the server split its
  presentation, which is the one thing this whole arrangement exists to avoid.
- **The use case returns the view**, as the existing four already do. Where a screen needs no
  operation — the plans catalogue, for instance — a named builder in the composition root is the use
  case, and it is still the thing the route calls.

**Rejected: a `Presenter` layer distinct from the use case.** It would make five names for a screen
(repository, domain model, use case, presenter, view) where the repository already demonstrates four,
and the existing four verticals would all have to be rewritten to gain a hop that decides nothing.

**Rejected: doing this in one pass.** Both shapes coexist while it happens — one screen per commit,
`check` green after each, which is this repository's own rule for migrations and the reason `B-92`
had a green point in the middle.

## Acceptance criteria

- AC: no product copy is composed in a routing file. `ProfileRouting` is the pilot and the proof.
- AC: no screen looks anything up while drawing. Its file imports no repository, no catalogue, no
  card factory and no clock, and holds none as a field. `TariffChangeScreen`'s `tariffs` parameter
  goes; `RoamingScreen` stops being a class that owns a `KonektClock`.

  **This replaces "every screen takes exactly one view", which is what this item said first and what
  the code refuted within the hour.** `PlansScreen.build(plans: List<Plan>)` looks nothing up and
  decides nothing: wrapping that list in a `PlansView` is the anemic hop this item's own rejected
  alternatives warn about, and a rule that demanded it would be ceremony charged to every future
  screen. The rule is the lookup, not the arity — a view appears where there are decisions to carry,
  and `ProfileView` earned its existence by carrying two resolved titles and a state.
- AC: at least the home install door, the empty-home banner and the tariff-change title are asserted
  **on the view**, without a tree walk. The tree walk stays where the subject really is the tree.
- AC: a guard refuses a screen file that imports a repository or a catalogue, and refuses a view type
  declared in a `*-shared-api` module. Both proved by mutation.
- AC: **no response changes.** This is a refactor, and the evidence is the existing screen tests
  passing unchanged on the copy they assert, plus `make e2e` green against a stand.
- AC: `docs/services/` records the rule, so the next screen is written this way rather than
  discovering it.

## Where the work is, measured

Read out of the sources rather than assumed, after the pilot:

| Screen | What it looks up while drawing |
|---|---|
| `HomeScreen` | `UsageCounterCards` and `RoamingPackageCards`, both injected factories |
| `RoamingScreen` | a `KonektClock` field — the live/waiting/ended split is decided at render time |
| `TariffChangeScreen` | `tariffs: List<Tariff>`, to turn two ids into two names |
| `ProfileScreen` | nothing, as of the pilot |
| `PlansScreen`, `PlanDetailScreen` | nothing — they render domain values and decide nothing |
| `PurchaseResultScreen`, `EsimWizardScreen`, `TopUpScreens` | nothing |

So the item is three screens and a guard, not eleven.

## Deliberately not in scope

- **Forms.** `LoginScreens` and `CustomPackageForm` answer with a `KompotFormResponse` and a
  `FormPatch`, which is a different shape with its own state — the patch is a diff, not a screen.
  They get the same treatment when the screens are done, as their own item.
- **The client.** Nothing here changes what goes over the wire, by construction.
- **kompot itself.** If it turns out the framework should offer a view/render split, that is a finding
  for [research-upstream-proposals](../research/research-upstream-proposals.md) and an issue.

## Anchors

| What | Where |
|---|---|
| The shape that already works | `server/src/main/kotlin/io/konekt/tariff/TariffUseCases.kt` (`TariffChangeView`) |
| The shape that does not | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt`, `HomeRouting.kt` |
| Copy composed in a route | `server/src/main/kotlin/io/konekt/screens/ProfileRouting.kt` |
| A view that still needs a lookup | `server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt` (`build(view, tariffs)`) |
| Decisions asserted through a tree | `server/src/test/kotlin/io/konekt/screens/HomeScreenTest.kt` |

## What was done

Four screens moved and one rule got a guard.

| Screen | Before | After |
|---|---|---|
| Profile | four repositories, and a sentence composed in `ProfileRouting` | `ViewProfileUseCase` -> `ProfileView` |
| Roaming | a class holding a `KonektClock`, ranking zones while drawing | `ViewRoamingUseCase` -> `RoamingView`, one instant |
| Tariff catalogue | a change record plus the catalogue, resolved in the renderer | `ViewTariffsUseCase` -> `TariffsView` |
| Tariff change | `build(view, tariffs)` | `build(view)`, both titles resolved |
| Home | six injections in the route, eight parameters, two of them renderers | `ViewHomeUseCase` -> `HomeView` |

**The clock came out of the card factories, and that is the defect the item did not know it had.**
`UsageCounterCards` and `RoamingPackageCards` each held a `KonektClock` and read it per card, and
`RoamingScreen` read a third one to rank its zones — so a home screen with three counters and two
travel packages could caption five cards against five instants, and a package could be ranked as
running and captioned as ended in one response. The repository already refuses to work this way:
`ExposedRoamingPackages.travelling()` filters every row against a single instant and says so in a
comment. The instant is an argument now, taken once per response and carried on the view.

**`PendingTariffChange` became one type.** The profile and the tariff catalogue both tell a subscriber
about a waiting change, and they were composing the same sentence in two places — one of them a
routing file. `PendingChangeReadsTheSameTest` asserts on the RENDERED text of both screens rather than
on the shared function, so a screen that goes back to writing its own is caught.

**`KoinGraphTest` gained five entries, and that is the reverse of a regression.** A route is not
verified — `by inject<T>()` is resolved at request time — so moving an assembly out of a route and
into a `factory` is what made those cross-module reaches visible to the graph check at all.

## Verified

`ScreensLookNothingUpTest`, three assertions, **each proved by its own mutation**: a screen importing
a catalogue, a screen calling `.now()`, and a `*View` declared in a `-shared-api` module. It also
asserts a floor on the number of screen files it found, because a source guard that finds nothing
passes.

`ViewProfileUseCaseTest` and `ViewRoamingUseCaseTest` assert the decisions on the view rather than
through a tree — including the travel screen's zone ordering, which had **no test at all**: it lived
in a private comparator and was reachable only by reading heading ids out of a component tree.

`:server:test` green; `build` green; against a rebuilt stand, 34 e2e and 16 `standTest` green, with no
response changed.

### The single-instant test was vacuous when first written

It gave the use case ONE zone, and `sortedBy` over a one-element list never calls its selector — so a
`rankOf(inZone, clock.now())` reading the clock per comparison passed it. Two zones, and the same
mutation fails with three instants instead of one. A test whose subject is *how many times is this
called* has to give it something to call it for.

### The item's own first acceptance criterion was wrong

It said every screen takes exactly one view. The code refuted that within the hour: `PlansScreen`
looks nothing up and decides nothing, and wrapping its list would be the anemic hop the item's
rejected alternatives warn against. The rule is the lookup, not the arity — amended above rather than
quietly satisfied.

## Anchors

| What | Where |
|---|---|
| The rule, and the only place it is enforced | `server/src/test/kotlin/io/konekt/screens/ScreensLookNothingUpTest.kt` |
| The pilot | `server/src/main/kotlin/io/konekt/screens/ProfileUseCases.kt` |
| One instant per response | `server/src/main/kotlin/io/konekt/roaming/RoamingUseCases.kt`, `server/src/main/kotlin/io/konekt/screens/HomeUseCases.kt` |
| The shared waiting-change sentence | `server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt`, `server/src/test/kotlin/io/konekt/tariff/PendingChangeReadsTheSameTest.kt` |
| The rule, written down | `docs/services/konekt-server.md` §3 |
