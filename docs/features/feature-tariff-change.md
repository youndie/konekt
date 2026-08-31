---
id: feature-tariff-change
title: Changing tariff — the second saga, and the one that is not about money arriving
type: feature
status: active
owner: unassigned
involved_services:
  - konekt-server
  - konekt-client
# NO CLIENT ENTRY. There were two screens; `B-102` removed them, and what is left is a saga driven
# over the DTO routes below. `screen-tariffs` still exists, marked deprecated, and says why.
client_entries: []
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

A subscriber is on a tariff — a monthly allowance — and can move to another one. The change does not
take effect when they ask for it. It takes effect at the next **billing
boundary**, and both tariffs are true until that date: the one they are on runs to the boundary and
the one they chose starts after it.

**This is the second saga in this build and the one that is not about money arriving.** A purchase
asks *spend this?*; a tariff change asks *change what you are on?* — a different refusal, a different
reversal, and the second is what shows petich's suspend/resume is not being used for one shape of
transaction only.

**AND IT HAS NO SUBSCRIBER-FACING SURFACE, deliberately, since `B-102`.** It was built by `B-21`,
given two screens by `B-86` — and those screens put a word on the profile that a subscriber could act
on, beside a *$5 / month* this build has never charged and has no machinery to charge. `B-102`
removed the catalogue, the change screen, the profile block, the two actions and the monthly price.

What survives is the saga itself, exercised over `/api/v1/tariff-changes` by
`TariffChangeScenarioTest`: request, suspend, confirm, and a change dated to a boundary. That is the
whole of what this vertical was ever for — petich's suspend/resume on a second shape of transaction —
and it needs no screen to demonstrate it. Recurring billing is a non-goal, stated as one in
[reference-scope](../services/reference-scope.md).

## 2. Business rules

| Rule | Where it is enforced |
|---|---|
| The change takes effect at the first of the next month, UTC | `BillingBoundary.nextAfter`, and the date is decided when the change is **requested** rather than when it is applied — a subscriber told "from the first" and confirming on the thirty-first gets the date they were shown |
| One change at a time | the saga refuses a second while one is pending (409) |
| The current tariff cannot be chosen | the server refuses it |
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
| The wire: the DTO resources and the payloads | `feature/tariff-shared-api/src/commonMain/kotlin/io/konekt/feature/tariff/shared/api/TariffApi.kt` |
| Why there is no screen, at the place a reader meets the tariff | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` |
| The table | `shared/db/src/main/resources/db/migration/V9__tariff_change.sql` |

## 4. Scenarios (BDD / test cases)

THREE SCENARIOS WENT WITH THE SCREENS (`B-102`) — the catalogue walk, the banner that withdrew every
offer while a change waited, and the change screen naming both tariffs. They described component
trees this server no longer builds, and a scenario kept after its subject is gone is the shape
`B-98` was filed about. The rule they asserted — both tariffs are true until the boundary — is a rule
about the LOG rather than about a screen, and it is still asserted, by `TariffChangeSagaTest` against
`ExposedTariffChanges.currentTariffId`.

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

**DTOs, and no kompot actions.** `POST /api/v1/tariff-changes` requests a change and answers a
`TariffChangeResponse`; `POST /api/v1/tariff-changes/{changeId}/confirmation` resumes the saga. Both
are ordinary JSON over the resources in `TariffApi.kt`.

There were two actions — `change_tariff` and `confirm_tariff_change`, registered by hand on each side
and named in `konektActionWireNames`. `B-102` removed them with the screens that composed them:
nothing sends an action no component carries, and wire vocabulary nobody speaks is the defect this
repository keeps filing. The reasoning for why they had to be actions rather than a `navigate` is
kept in `TariffApi.kt`, because it is the right reasoning and the next screen to need it should not
re-derive it.

## 6. Out of scope

* **Downgrade rules.** Any tariff can be chosen from any tariff (`B-21`).
* **Proration.** The change is dated to a boundary precisely so that it is not needed; an immediate
  change makes proration the centre of the feature, and this build has nothing to say about it.
* **A per-subscriber billing cycle.** The boundary is the first of the month, UTC, for everybody —
  `DayFormat` pins the same zone and states it as a real limitation.
* **Charging for the tariff.** The boundary this saga dates a change to is the one thing in the build
  that resembles a billing period, and nothing ever crosses it with an invoice. A row in
  [reference-scope](../services/reference-scope.md) says why recurring billing is not here, and
  `B-102` is what stopped the product claiming otherwise.
* **A subscriber-facing surface for any of this.** The saga is reachable over HTTP and by nothing a
  subscriber can press.
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
  called Standard. It cost nothing while no screen displayed them, `B-86` put them on one, and
  `B-102` took the screen away again. The corrected numbers stayed: a catalogue that is right is
  cheaper to keep than a note explaining why it is wrong and harmless.
- **A tariff has no price.** `Tariff` carries an id, a title and an allowance, and the `monthlyPrice`
  that used to sit beside them was read by nothing the moment the screens went. It is deleted rather
  than kept for later — the field WAS the claim `B-102` was filed about, one screen away from being
  shown again.
