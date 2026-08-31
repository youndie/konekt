---
id: B-103
title: "The travel screen shows what you own and offers no way to buy, so travel packages read as unavailable"
status: open
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
