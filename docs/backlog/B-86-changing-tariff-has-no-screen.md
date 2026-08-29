---
id: B-86
title: "Changing tariff has a saga, a table, a confirmation and no screen: only an e2e test can reach it"
status: done
priority: P0
size: M
stage: stage-m7-completeness
---

# B-86 — A whole vertical whose only user is a test

[B-21](B-21-tariff-change.md) closed with a working feature. What it does not have is any way in:

- `Shell.graph()` lists eight destinations and none of them is a tariff screen;
- no component anywhere sends `ChangeTariffRequest` — the only senders are
  `e2e/src/test/kotlin/io/konekt/e2e/TariffChangeScenarioTest.kt` and the server's own tests;
- `:client` does not depend on `feature:tariff-shared-api` at all. Seven `*-shared-api` modules are
  on its dependency list and that one is not, so the client could not decode the request even if a
  screen posted it;
- `StaticTariffCatalogue` holds three tariffs nothing displays.

This is the shape [B-56](B-56-unreachable-screen-guard.md) was written for — *nothing fails when a
screen the server serves is the destination of no action anywhere* — and it slipped past because
there is no screen at all: the guard watches served screens, and an unserved feature is invisible to
it.

For a reference build this is the expensive kind of gap. A tariff change is the second saga in the
product and the one that is **not** about money arriving; it is about a subscription changing under
a subscriber who has to agree to it. That is the case petich's suspend/resume exists for, and today
it is demonstrated only in a test harness where the confirmation is a function call.

- **The decision: give it the screens it already has the server for** — the current tariff on the
  profile screen, a catalogue with the current one marked, and the confirmation stating what changes
  and when it takes effect. The saga, the routes and the repository are done; this is the wire and
  the tree.
- **The confirmation is the reason to do this rather than to drop it.** A purchase's confirmation
  answers "spend this?", and a tariff change's answers "change what you are on?" — a different
  refusal, a different reversal, and the second is what proves the engine is not being used for one
  shape of transaction only.
- **The rejected alternative is to delete the vertical.** Three tariffs, a saga and a migration are
  already carried by every build and every deploy; deleting them costs the same work as finishing
  them and removes a demonstration.
- This item does **not** add downgrade rules — [B-21](B-21-tariff-change.md) recorded *any tariff
  can be chosen from any tariff* as out of scope and that stands — and does not move the catalogue
  out of Kotlin, which is a non-goal ([B-80](B-80-the-non-goals-are-nowhere.md)).

- AC: a subscriber reaches the tariff catalogue from the profile tab, chooses one, sees what it costs
  and what changes, confirms, and lands on a result screen that names the outcome.
- AC: the refusal path is a screen with a reason, not a status code, the way
  [B-68](B-68-a-refused-purchase-never-says-why.md) settled it for purchases.
- AC: `:client` depends on `feature:tariff-shared-api`, and the action module is registered in the
  application's `Json` — [B-73](B-73-the-stand-registered-no-actions.md) is what happens otherwise.
- AC: the destinations are in `Shell.graph()`, so `EveryScreenIsReachableTest` covers them.
- Anchors: `server/src/main/kotlin/io/konekt/screens/Shell.kt`,
  `server/src/main/kotlin/io/konekt/tariff/TariffData.kt`,
  `feature/tariff-shared-api/src/commonMain/kotlin/io/konekt/feature/tariff/shared/api/TariffApi.kt`,
  `client/build.gradle.kts`, `e2e/src/test/kotlin/io/konekt/e2e/TariffChangeScenarioTest.kt`.

## What was done

The saga, the routes and the repository were done; this is the wire and the tree.

**The way in.** The profile screen names the tariff the line is on — the answer to "what am I on" was
nowhere in the product — and offers `Change tariff` as a `navigate` to `app://tariffs`. A pending
change appears there too, as a sentence: a subscriber who asked for one and closed the application
looks for it where they look for what they are on.

**The catalogue**, `GET /api/v1/screens/tariffs`. Every tariff, the current one badged *Your tariff*
and carrying no action, the others carrying `ChangeTariffAction`. **No new wire type** — a tariff is
drawn with `PlanCardComponent`, because that is what the component already says and a
`TariffCardComponent` would be a client release for a card differing from an existing one in nothing
but the word.

**The change**, `GET /api/v1/screens/tariff-changes/{changeId}`. Both tariffs and the date, because
both are true until the boundary; a sentence per outcome; and the confirmation, which is the only
control the screen ever has — no way-out button, since the bottom bar is the way out and a second
primary is what [B-71](B-71-two-primary-buttons-on-the-completed-purchase.md) removed from the
purchase result.

**A change already waiting withdraws every offer** and puts the way back to it at the top. Not
tidiness: the server answers 409 to a second change, and a card that accepts a press and is then
refused is worse than one that does not accept it.

### What it turned up that the item did not name

- **The view was built twice and the copies had begun to differ.** `StartTariffChangeUseCase` derived
  `requiredAction` from the saga's status and `ConfirmTariffChangeUseCase` wrote `null` in. A third
  copy for the screen is how a screen ends up disagreeing with the route about whether a change is
  still waiting — which is exactly [B-66](B-66-the-esim-qr-is-unreachable-through-the-app.md). One
  `tariffChangeViewOf`, three callers, and the owner check inside it.
- **The end-to-end stand registered neither `shell` nor `tariff` actions.** kompot answers an
  unregistered action with `UnknownAction`, so a scenario reading a button's action gets null and
  concludes the screen has no control — indistinguishable from a server that drew none. That is
  [B-73](B-73-the-stand-registered-no-actions.md) again, in the half that was left. The comment there
  said "the three the CLIENT registers" and there are five; the count went stale the moment a fourth
  action existed.
- **The conformance walk now reaches both screens**, and reaching the change screen meant giving the
  walk a `changeId` the way it is given an `orderId`: it starts a change and leaves it **waiting**,
  which is the branch that draws a control. Declaring the endpoint unwalkable instead would have been
  an exemption in the guard that exists to find exactly this.
- **No `TARIFF_CHANGE_DEEPLINK`.** It was written and then removed: nothing navigates to a tariff
  change — it is reached by an action whose answer carries the id — and a constant used by nothing is
  the shape this repository files as a defect.

## Verified

- `TariffScreensTest` — four cases, **proved by mutation**: making the current tariff pressable and
  making the confirmation unconditional each turn it red, naming the state.
- `TariffScreenScenarioTest` — the whole walk against a running stand, starting from what each screen
  offered rather than from an id typed into the test. 31 e2e tests, 0 failures.
- `./gradlew check` green; `TckCoverageTest` reaches 21 endpoints where it reached 19.

### Walked on a device, and it found two defects every tree assertion had passed over

The flow was driven end to end on a Pixel 6a against the stand — profile, catalogue, change,
confirmation. Two things were wrong on the screen and right in every test:

- **The current tariff read "Sold out", in red, and the "Your tariff" badge never appeared.** The
  screen used `PlanStates.SOLD_OUT` to make the card unpressable; the client renders that state as
  those words, in the slot the badge would have used. The server test asserted the badge and the
  absent action and both were correct. **The lesson is narrower than "test the render": a vocabulary
  value carries the meaning the OTHER side draws, and `sold_out` means *not for sale*, which a tariff
  somebody is on is not.** What actually makes a card unpressable is `action == null`, on its own.
- **The allowances were written in a base nothing else uses.** `TariffData` said `2_000`, `10_000`,
  `50_000` — decimal thousands — while `UsageUnits` divides by 1024 like the rest of the build, so the
  catalogue offered *9.8 GB* for the tariff called Standard and *48.8 GB* for Max. It cost nothing
  while no screen displayed them, and `B-86` is the item that displayed them.

Both are guarded now: no card may carry `sold_out` (asserted over every card, because the next state
added will be borrowed the same way), and the three allowances must read as whole gigabytes.

### And it demonstrated the boundary, by accident

The screens drew on an Android build compiled **before any of this existed** — they are a server
response. Pressing a tariff on that same build did nothing, and the log said why:

```
konekt-android: no handler for UnknownAction(originalType=change_tariff)
```

A component is generated into the registry; an action is registered by hand on both sides. So a new
action is a client release, and it degrades **silently** — a button that looks right and does nothing
— where an unknown component degrades visibly. `operator-boundaries.md` now carries the row and the
difference; the measurement is this walk.

## What is deliberately not in scope

Downgrade rules — [B-21](B-21-tariff-change.md) recorded *any tariff can be chosen from any tariff* as
out of scope and that stands — and moving the catalogue out of Kotlin, a non-goal in
[reference-scope](../services/reference-scope.md).

**The documentation layers.** This vertical has screens now and still has no `docs/features/` or
`docs/screens/` document, and neither does the custom package builder. That is one job for both rather
than half of it here, and it is [B-93](B-93-two-verticals-have-screens-and-no-documents.md).

## Anchors

| What | Where |
|---|---|
| The two screens | `server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt` |
| Their routes | `server/src/main/kotlin/io/konekt/tariff/TariffScreenRouting.kt` |
| The wire: actions, screen resources, the deeplink | `feature/tariff-shared-api/.../TariffApi.kt` |
| The way in | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt`, `ProfileRouting.kt`, `Shell.kt` |
| The client's handler | `client/src/commonMain/kotlin/io/konekt/client/app/ChangeTariff.kt` |
| One view builder, three callers | `server/src/main/kotlin/io/konekt/tariff/TariffUseCases.kt` |
