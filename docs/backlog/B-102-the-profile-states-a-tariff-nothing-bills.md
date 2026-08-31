---
id: B-102
title: "The profile states a tariff the subscriber never chose and nothing ever charges for"
status: open
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

## Anchors

| What | Where |
|---|---|
| The word on the screen | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` |
| The price nothing takes | `server/src/main/kotlin/io/konekt/tariff/TariffData.kt` |
| The saga worth keeping | `server/src/main/kotlin/io/konekt/tariff/TariffUseCases.kt` |
| Where the answer must be recorded | `docs/services/reference-scope.md` |
