---
id: screen-tariffs
title: The tariff catalogue and one change — two screens that no longer exist
type: client_screen
platform: [jvm, android, ios]
status: deprecated
entry:
  jvm: "none — GET /api/v1/screens/tariffs and GET /api/v1/screens/tariff-changes/{changeId} were removed by B-102 and answer 404"
parent_feature: feature-tariff-change
calls_api:
  - api-openapi
source: docs/backlog/B-102-the-profile-states-a-tariff-nothing-bills.md
---

# Screen: the tariff catalogue, and one change — removed

> **These screens are gone.** `B-102` removed them, and this document stays because a closed item's
> acceptance criteria name it ([B-93](../backlog/B-93-two-verticals-have-screens-and-no-documents.md))
> and because a reader who finds nothing at `app://tariffs` deserves to learn that it was a decision
> rather than a gap. Deleting the file would have made the two indistinguishable, which is the
> failure [reference-scope](../services/reference-scope.md) exists to prevent.

## Why they went

The catalogue drew every tariff with its **price per month**, and the profile named the tariff a
subscriber was on beside a `Change tariff` control. Nothing in this build has ever charged a monthly
price: there is no scheduler, no billing period and no recurring charge anywhere in it. So the screens
stated a commitment the product does not have, next to a package that really was bought and really was
paid for — reported by somebody using the application, and filed as
[B-102](../backlog/B-102-the-profile-states-a-tariff-nothing-bills.md).

Of that item's three ways out, the third was taken: remove the tariff from the subscriber's view and
keep the saga as the demonstration it is. Billing it would have been a vertical — scheduler,
proration, failure handling — demonstrating none of the six toolkits this build is about.

## What went, exactly

| Gone | Where it was |
|---|---|
| `GET /api/v1/screens/tariffs`, `GET /api/v1/screens/tariff-changes/{changeId}` | `server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt`, `TariffScreenRouting.kt` |
| `app://tariffs`, and the `Shell.graph()` route for it | `server/src/main/kotlin/io/konekt/screens/Shell.kt` |
| The tariff block and its `Change tariff` control | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` |
| `change_tariff`, `confirm_tariff_change`, and the client handler that answered them | `feature/tariff-shared-api/.../TariffApi.kt`, `client/.../app/ChangeTariff.kt` |
| `Tariff.monthlyPrice` — the price itself | `server/src/main/kotlin/io/konekt/tariff/TariffDomain.kt` |

## What survives

The saga. `POST /api/v1/tariff-changes` requests a change, the saga suspends, and
`POST /api/v1/tariff-changes/{changeId}/confirmation` resumes it — petich's suspend/resume on a second
shape of transaction, which is the whole of what this vertical was ever for. `TariffChangeScenarioTest`
drives it end to end and `TariffChangeSagaTest` covers the boundary rule.

The reasoning worth keeping outlived the screens and is not buried here: why a change had to be an
action rather than a `navigate` is in `TariffApi.kt`, and why the profile no longer names a tariff is
at the top of `ProfileScreen.kt` — the place a reader actually meets the question.

## 0a. Code anchors

| What | File |
|---|---|
| Where the tariff used to be, and why it is not | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` |
| The saga that survived them | `server/src/main/kotlin/io/konekt/tariff/TariffUseCases.kt` |
| The wire that is left | `feature/tariff-shared-api/src/commonMain/kotlin/io/konekt/feature/tariff/shared/api/TariffApi.kt` |

## Quirks worth not losing

Three things these screens taught, kept because the next screen can repeat all three:

- **`sold_out` is a word, not just a state.** Making the current tariff's card unpressable with
  `sold_out` drew **Sold out** in red over the subscriber's own tariff. What makes a card unpressable
  is `action == null`. Found by a screenshot from a device; every tree assertion had passed over it.
- **No new component type for a new noun.** A tariff was drawn with `plan_card`. A `tariff_card` would
  have been a client release for a card differing from an existing one in nothing but the word.
- **There is no success tone.** The vocabulary is `info`, `low`, `error`, and a confirmed change used
  `info` with the words carrying the outcome.
