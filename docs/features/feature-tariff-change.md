---
id: feature-tariff-change
title: Changing tariff — the second saga, and the one that is not about money arriving
type: feature
status: active
owner: unassigned
involved_services:
  - konekt-server
  - konekt-client
client_entries:
  - screen-tariffs
api:
  # No hand-written endpoint document for these four routes. They are in the generated one, which is
  # derived from the routing tree and therefore cannot be wrong about paths, methods or auth tiers —
  # and writing a second description of them by hand is how a document starts disagreeing with the
  # server. What the generated one cannot say is why, and that is what this file is for.
  - api-openapi
tags: [tariff, saga, confirmation, billing-boundary]
---

# Changing tariff

## 1. Overview

A subscriber is on a tariff — a monthly price and a monthly allowance — and can move to another one.
The change does not take effect when they ask for it. It takes effect at the next **billing
boundary**, and both tariffs are true until that date: the one they are on runs to the boundary and
the one they chose starts after it.

**This is the second saga in this build and the one that is not about money arriving.** A purchase
asks *spend this?*; a tariff change asks *change what you are on?* — a different refusal, a different
reversal, and the second is what shows petich's suspend/resume is not being used for one shape of
transaction only.

It was built by `B-21` and had **no way in at all** until `B-86`: no component sent a
`ChangeTariffRequest`, `:client` did not depend on the contract, and three tariffs sat in a catalogue
nothing displayed. The only user of the whole vertical was an end-to-end test.

## 2. Business rules

| Rule | Where it is enforced |
|---|---|
| The change takes effect at the first of the next month, UTC | `BillingBoundary.nextAfter`, and the date is decided when the change is **requested** rather than when it is applied — a subscriber told "from the first" and confirming on the thirty-first gets the date they were shown |
| One change at a time | the saga refuses a second while one is pending (409), and the catalogue screen withdraws every offer while one waits |
| The current tariff cannot be chosen | the card for it carries no action; the server would refuse it |
| A subscriber who has never changed is on the catalogue's default | `TariffCatalogue.default`, a property of the catalogue rather than a column with a default — renaming a deployment's base tariff must not need a migration |
| The current tariff is the newest **applied** row whose boundary has passed | `ExposedTariffChanges.currentTariffId`; without the date filter a confirmed change becomes current the moment it is confirmed, which is exactly what "at the next boundary" is not |
| Any tariff may be chosen from any tariff | there are no downgrade rules; `B-21` recorded that as out of scope and it stands |

## 3. Code anchors

| What | File |
|---|---|
| The saga's payload, the boundary, the tariff type | `server/src/main/kotlin/io/konekt/tariff/TariffDomain.kt` |
| The catalogue and the change log | `server/src/main/kotlin/io/konekt/tariff/TariffData.kt`, `TariffPorts.kt` |
| Start, confirm, and **one** view builder for all three callers | `server/src/main/kotlin/io/konekt/tariff/TariffUseCases.kt` |
| The interceptors | `server/src/main/kotlin/io/konekt/tariff/TariffInterceptors.kt` |
| The two routes that answer DTOs | `server/src/main/kotlin/io/konekt/tariff/TariffRouting.kt` |
| The two screens and their routes | `server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt`, `TariffScreenRouting.kt` |
| The wire: actions, screen resources, deeplink | `feature/tariff-shared-api/src/commonMain/kotlin/io/konekt/feature/tariff/shared/api/TariffApi.kt` |
| The client's handler | `client/src/commonMain/kotlin/io/konekt/client/app/ChangeTariff.kt` |
| The way in | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt`, `ProfileRouting.kt` |
| The table | `shared/db/src/main/resources/db/migration/V9__tariff_change.sql` |

## 4. Scenarios (BDD / test cases)

### Scenario: a subscriber reaches the catalogue and changes tariff

```gherkin
Given a signed-in subscriber on the default tariff
When they open the profile tab
Then it names the tariff they are on
And it offers "Change tariff", which navigates to app://tariffs
When they open the catalogue
Then the tariff they are on carries the badge "Your tariff" and no action
And every other tariff offers a change
When they choose one and confirm it
Then the change screen names both tariffs and the date it takes effect
And it offers no confirmation any more
```

**Automated:** `e2e TariffScreenScenarioTest`, `server TariffScreensTest`

### Scenario: a change already waiting withdraws every offer

```gherkin
Given a subscriber with a change awaiting confirmation
When they open the tariff catalogue
Then no tariff offers a change
And a banner says a change is waiting and names the tariff it is to
And the banner offers the way back to that change
```

**Automated:** `e2e TariffScreenScenarioTest`, `server TariffScreensTest`

### Scenario: both tariffs are true until the boundary

```gherkin
Given a confirmed change from Basic to Max
Then the change screen still names Basic as the tariff they are on
And it names Max as the one they are changing to
And it names the date the change takes effect
```

**Automated:** `e2e TariffScreenScenarioTest`, `server TariffScreensTest`

### Scenario: the saga suspends until the subscriber answers

```gherkin
Given a requested tariff change
Then the saga is PENDING_SIGNATURE and its view carries requiredAction CONFIRM
And nothing has changed about the subscriber's tariff
When the confirmation arrives
Then the saga completes and the change is recorded against the boundary
```

**Automated:** `e2e TariffChangeScenarioTest`, `server TariffChangeSagaTest`

## 5. Wire format

Two actions, both registered by hand on each side and both named in `konektActionWireNames`:
`change_tariff` carrying a `tariffId`, and `confirm_tariff_change` carrying a `changeId`. A `navigate`
cannot express either — the change does not exist until the press, and confirming resumes a saga
rather than moving anywhere.

The two screens are ordinary component trees. **No new component type**: a tariff is drawn with
`plan_card`, because that is what the component already says and a `tariff_card` would be a client
release for a card differing from an existing one in nothing but the word.

## 6. Out of scope

* **Downgrade rules.** Any tariff can be chosen from any tariff (`B-21`).
* **Proration.** The change is dated to a boundary precisely so that it is not needed; an immediate
  change makes proration the centre of the feature, and this build has nothing to say about it.
* **A per-subscriber billing cycle.** The boundary is the first of the month, UTC, for everybody —
  `DayFormat` pins the same zone and states it as a real limitation.
* **The catalogue as data.** Three tariffs in Kotlin, a non-goal in
  [reference-scope](../services/reference-scope.md).

## 7. Quirks

- **The view was built twice and the copies had begun to differ.** `StartTariffChangeUseCase` derived
  `requiredAction` from the saga's status and `ConfirmTariffChangeUseCase` wrote `null` in. A screen
  reading a change would have been a third copy — and that is precisely how the eSIM wizard came to
  disagree with itself about the issued profile (`B-66`). There is one `tariffChangeViewOf` now, and
  the owner check lives inside it.
- **The allowances were written in a base nothing else uses.** `2_000`, `10_000`, `50_000` — decimal
  thousands, while `UsageUnits` divides by 1024 — so the catalogue offered *9.8 GB* for the tariff
  called Standard. It cost nothing while no screen displayed them, and `B-86` is the item that
  displayed them.
- **The current tariff is drawn `available`, not `sold_out`.** Using `sold_out` to make the card
  unpressable was the first attempt; the client renders that state as the words **Sold out**, in red,
  in the slot the badge would have used. What makes a card unpressable is `action == null`.
