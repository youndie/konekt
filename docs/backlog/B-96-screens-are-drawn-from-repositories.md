---
id: B-96
title: "Screens are drawn from repositories, so every presentation decision is testable only through a tree"
status: open
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
- AC: every screen's entry point takes one view plus an optional `nav`, and its file imports no
  repository, catalogue or card-factory type. `TariffChangeScreen`'s `tariffs` parameter goes.
- AC: at least the home install door, the empty-home banner and the tariff-change title are asserted
  **on the view**, without a tree walk. The tree walk stays where the subject really is the tree.
- AC: a guard refuses a screen file that imports a repository or a catalogue, and refuses a view type
  declared in a `*-shared-api` module. Both proved by mutation.
- AC: **no response changes.** This is a refactor, and the evidence is the existing screen tests
  passing unchanged on the copy they assert, plus `make e2e` green against a stand.
- AC: `docs/services/` records the rule, so the next screen is written this way rather than
  discovering it.

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
