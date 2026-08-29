---
id: B-78
title: "Every completed install mints another eSIM, and every purchase offers to do it again"
status: done
priority: P0
size: M
stage: stage-m4-proof
epic: feature-esim-lifecycle
---

# B-78 — One account, one device, one eSIM — and the product does not think so

The intended model is one subscriber, one line, one profile: an eSIM is how the line is reached, and
packages are what the line may spend. Nothing in the schema disagrees — `entitlement`,
`usage_counter` and `roaming_package` all hang off `subscriber_id` and **none of them names an
eSIM** — so a second package has never created a second profile.

The install FLOW does. Measured on the stand: walking the wizard to `Done` and walking it again leaves
the subscriber holding

```
8944655936528126299 installed
8944764742821585431 installed
8944008669787442586 ready
```

Three profiles on one line, from one person pressing buttons that were offered to them.

## Two halves, and the second is why it reads as "an eSIM per package"

**Issuing is idempotent per RUN and not per LINE.** A profile is minted on the way into `activate`
when `draft.issuedEsimId` is null, and a finished run's `Finish` is followed by a `GET` that correctly
starts a fresh run — with a fresh draft. So each completed install mints another profile, bounded only
by the SM-DP+ mock's device limit of eight.

**The door is on the purchase.** `PurchaseResultScreen.completed` draws `Install eSIM`
unconditionally, so every package a subscriber buys offers to install an eSIM again. The home screen
already asks the right question — its banner appears only while something is not on a device
([B-69](B-69-held-is-not-installed.md)) — and the purchase result never got the same rule.

Together they read as "each package comes with its own eSIM", which is what a person looking at this
product concluded, and the flow then makes it true.

## What was done

1. **The line holds at most one profile.** Entering `activate` on a subscriber who already has a
   non-terminated one shows THAT code rather than issuing another. The draft-level idempotence stays
   for retries inside a run; this adds the line-level rule that was missing.
2. **`Install eSIM` appears only when the line has nothing installed**, the same condition the home
   banner uses. On a line that already has one, a completed purchase says the package is active and
   stops there.
3. **The slot rule keeps its meaning.** `SmDpPlus.capacityFor(profilesHeld)` allows eight because a
   DEVICE may hold eight; that is the manager's rule and it stays. Ours is one, and it is enforced
   before the manager is asked — the mock is never called for a line that already has a profile.

A subscriber who already holds one is **shown it** rather than refused. They pressed `Install eSIM`
to get at their code; the code they need is the one already issued, and refusing would be correct
about the rule and useless about the errand.

`EsimHoldings.needsInstalling` is where the "is there anything to install" question now lives, so the
home banner and the purchase result cannot answer it differently — which is how they came to differ
in the first place.

## Verified

On the stand, three full walks through the wizard:

```
after the first install:  8944106153847460954 installed
after two more walks:     8944106153847460954 installed
the completed purchase offers:  purchase-done | Done
```

One profile, the same ICCID, and the door is gone from a purchase on a line that already has an eSIM.
Guarded by `EsimWizardViewsAgreeTest` (installing twice mints once, with a vacuity check that the
manager was asked exactly once) and `PurchaseResultScreenTest` (the door is offered for a line with
nothing and for one not yet installed, and withheld for one that is). Both proved by mutation.

## What is deliberately not in scope

A second profile meaning a second DEVICE — a watch, a tablet on the same line. That is a feature and
this is not it: today a second profile means nothing at all, and `EsimCardComponent` labels every one
of them `New line`, so two would be two identical cards.

## Anchors

| What | Where |
|---|---|
| Where a profile is minted | `feature/esim-server-domain/.../EsimWizardUseCases.kt` (`AdvanceEsimWizardUseCase`) |
| The unconditional door | `feature/purchase-server-data/.../PurchaseResultScreen.kt` (`completed`) |
| The rule the home screen already has | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` (`installBanner`) |
| What holds what | `shared/db/.../CoreTables.kt`, `feature/*/…Table.kt` |
