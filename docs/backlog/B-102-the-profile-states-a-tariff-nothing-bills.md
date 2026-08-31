---
id: B-102
title: "The profile states a tariff the subscriber never chose and nothing ever charges for"
status: done
priority: P2
size: M
stage: stage-m7-completeness
---

# B-102 — Two words for one thing, and only one of them takes money

A subscriber who has bought the Home package opens the profile and reads:

```
Tariff
Basic                 [Change tariff]
```

They did not choose Basic. Nothing asked them to. And **nothing has ever charged them for it** —
reported by somebody using the application, and true in the code: `StaticTariffCatalogue` prices
`tr-basic` at `$5 / month`, the change saga moves between tariffs, the profile and the catalogue draw
them, and there is no monthly billing anywhere in this build. The tariff is a commitment the product
states and does not have.

Beside a package that WAS bought and WAS paid for, it reads as a second, invisible subscription.

## The two concepts, as they actually stand

| | plan | tariff |
|---|---|---|
| bought | yes, through the purchase saga, once | never |
| priced | `$15` for Home 20 GB · 30 days | `$5/month`, drawn and never taken |
| grants | counters, an allowance | nothing |
| changed by | buying another | a saga of its own, with a confirmation |
| visible as | counters on the home screen | a word on the profile |

The tariff vertical is real work — `B-21` built the saga, `B-86` gave it screens — and what it
demonstrates is petich's suspend/resume on a second shape of transaction. That is worth keeping. What
is not worth keeping is a monthly price on a screen when nothing bills monthly.

## The decision, and it is a choice this item should not make alone

Three ways out, and the item deliberately does not pick:

1. **Say what is true.** The tariff keeps its screens and stops showing a monthly price, or shows it
   with the honest note that this build does not bill. Cheapest, and leaves the product coherent.
2. **Bill it.** A recurring charge is a scheduler, a billing period, proration, failure handling and a
   refusal path — a vertical, not a fix, and one that demonstrates no toolkit this reference is about.
3. **Remove the tariff from the subscriber's view.** Keep the saga as the demonstration it is and stop
   putting a word on the profile that a subscriber can act on.

**Whichever is chosen, `docs/services/reference-scope.md` gains a row** — an absence with a reason and
an absence without one look identical, which is that document's whole purpose.

## Acceptance criteria

- AC: a subscriber can no longer read a monthly price for something that will never be charged.
- AC: whichever way it goes, the reason is written where the next reader meets the tariff — not only
  in this item.
- AC: if the tariff stays on the profile, its screens still work end to end and the e2e scenario
  still covers the change saga; if it goes, the saga's demonstration survives somewhere.
- AC: `reference-scope.md` says whether recurring billing is a non-goal and why.

## What was done — the third way out

**The tariff is gone from the subscriber's view. The saga is not.**

| Removed | Kept |
|---|---|
| The tariff block and `Change tariff` on the profile | `POST /api/v1/tariff-changes` and its confirmation route |
| `GET /api/v1/screens/tariffs`, `GET /api/v1/screens/tariff-changes/{changeId}`, `app://tariffs` | the saga, its interceptors, its sweeper claim, its boundary rule |
| `change_tariff`, `confirm_tariff_change` and the client handler for them | `TariffChangeScenarioTest` end to end, `TariffChangeSagaTest` on the boundary |
| `Tariff.monthlyPrice` | `Tariff`'s id, title and allowance — a change is still visibly a change |

Three things beyond the item as written, each because leaving them would have re-created it:

- **The two actions went too.** Nothing composes an action once no screen carries it, and wire
  vocabulary nobody speaks is what this repository files as a defect. Same for the two `@Resource`
  classes and the deeplink: an address nothing answers is a 404 waiting for whoever believes the file.
- **The price itself went**, not just its display. `monthlyPrice` was read by nothing the moment the
  screens left, and it *was* the claim this item is about — one screen away from being shown again.
- **Three BDD scenarios went** with the screens they described. The rule they were really about —
  both tariffs true until the boundary — is a rule about the change log, and `TariffChangeSagaTest`
  still asserts it.

`OpenApiDocumentTest`'s endpoint count moved 38 → 36, by hand, which is what that constant is for.

## Where the reason is written

Not only here — the second AC asked for exactly that:

| Where a reader meets the tariff | What it now says |
|---|---|
| `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` | why the block is gone, and that the saga is demonstrated over the DTO routes |
| `server/src/main/kotlin/io/konekt/tariff/TariffDomain.kt` | why a `monthlyPrice` could not stay on a build with no billing period |
| `feature/tariff-shared-api/.../TariffApi.kt` | why the actions, resources and deeplink went, and that the saga did not |
| [screen-tariffs](../screens/screen-tariffs.md) | `deprecated`, and the whole story — kept so a reader finding nothing at `app://tariffs` can tell a decision from a gap |
| [feature-tariff-change](../features/feature-tariff-change.md) | a feature reachable over HTTP and by nothing a subscriber can press |
| [reference-scope](../services/reference-scope.md) | **recurring billing** as a non-goal, with why and with what would end it |

## Anchors

| What | Where |
|---|---|
| The word on the screen | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` |
| The price nothing takes | `server/src/main/kotlin/io/konekt/tariff/TariffData.kt` |
| The saga worth keeping | `server/src/main/kotlin/io/konekt/tariff/TariffUseCases.kt` |
| Where the answer must be recorded | `docs/services/reference-scope.md` |
