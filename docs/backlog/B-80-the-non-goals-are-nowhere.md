---
id: B-80
title: "Nothing states what this build is deliberately not, so every absence reads as unfinished work"
status: done
priority: P0
size: S
stage: stage-m6-reframe
blocked_by: [B-79]
---

# B-80 — An absence with a reason and an absence without one look identical

`operator-boundaries.md` answers *what does a change cost*. Nothing answers *what is deliberately
absent*. The two are different questions and only the first one is written down.

The result is that a reader finding no admin surface cannot tell whether it is a decision or a
to-do, and the backlog does not settle it either: 77 items closed, and none of them says "no
management surface, on purpose". Meanwhile the reasons exist and are good — they are just scattered
through source comments where only somebody already reading that file will find them:

```
// A table with a seed migration would look more finished and would be the same fiction with a
// schema around it
                          feature/purchase-server-data/.../StaticPlanCatalog.kt

// Android joins with the item that first needs an .aar.
                          client/build.gradle.kts
```

- **The decision: one document, `docs/services/reference-scope.md`, listing each non-goal with the
  reason and with what would have to change if it stopped being one.** A non-goal with no reason is
  indistinguishable from an oversight; a non-goal with no "what it would take" is indistinguishable
  from a refusal.
- The list, from what was verified in the code rather than from imagination: a management surface
  (no admin route, no API, no CLI); the catalogue as data rather than as Kotlin; a real BSS/OCS
  seam (balance, ledger and counters are ours); a real SM-DP+ contract — `SmDpPlus` has two methods
  and GSMA's ES2+ has an order lifecycle, an EID and a notification that says a profile was
  installed, where here a button says it; a real PSP (`PaymentGateway.settle` is one synchronous
  call with no capture, void, refund or 3-DS); a real SMSC (`OtpDelivery` is not even `suspend` and
  returns nothing); multi-tenancy; localisation; presence in an app store.
- **The rejected alternative is to keep these as backlog items**, which is what they look like now.
  An item is a promise with no date on it, and eight promises nobody intends to keep are worse for a
  reader than one page saying "not this".
- **The second rejected alternative is to fold this into `operator-boundaries.md`.** That document
  has one job — the price list of a rebrand — and it is good because it does not also apologise.
- This item does **not** decide the ones the reframe leaves genuinely open: Android
  ([B-85](B-85-the-client-has-no-android-target.md)) and the screens that exist on the server and
  nowhere else are work, not boundaries, and they stay in the backlog.

- AC: `docs/services/reference-scope.md` exists, is in the coverage map in `docs/README.md`, and for
  each non-goal names the reason and the shape of the change that would end it.
- AC: every "Not covered" line in the closed items that describes a **standing** absence rather than
  one item's scope is either in that document or deliberately left out, and the document says which
  items it drew from.
- AC: `make check` is green — `coverage_map.py` fails on a services document that is not in the map.
- Anchors: `docs/services/reference-scope.md` (new), `docs/README.md`,
  `docs/services/operator-boundaries.md`,
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/StaticPlanCatalog.kt`,
  `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/EsimPorts.kt`,
  `feature/auth-server-domain/src/main/kotlin/io/konekt/feature/auth/server/domain/AuthPorts.kt`.

## What was done

[`docs/services/reference-scope.md`](../services/reference-scope.md), ten rows, each with both halves
the item asked for — the reason, and the shape of the change that would end it. The ten are the ones
listed above plus one: **more than one server replica**, which
[B-91](B-91-a-second-replica-loses-live-updates.md)'s decision puts here explicitly. Its row says the
chart guard is not built, because the boundary exists in the code today and its enforcement does not.

Three things beyond the list itself:

- **A "not on this list" section**, which is the half that keeps the document from becoming a place
  to file work. Android, the iOS device build, and the two verticals with no screen are named there
  as work, with their items — an absence that is on the table would otherwise read as settled.
- **A provenance section.** The AC asks which closed items the rows drew from, and it also asks that
  the ones left out say so: `B-40`'s missing top-up in the history and `B-42`'s uncovered native test
  tasks are one item's scope and a gap in a guard, not boundaries, and the document says that rather
  than silently omitting them.
- **Three entry points now point at it** — `README.md` in the paragraph that lists the absences,
  `CLAUDE.md` where `B-79` left a bare item reference, and the coverage map, which is what
  `coverage_map.py` checks.

Verified: `make check` green, `Services (4)` → `(5)` and the map matches the files on disk.

## What is deliberately not in scope

The rows about a rebrand's **price**. `operator-boundaries.md` is untouched here and stays the
document with one job; the two are cross-linked in both directions and the new document opens by
saying which question it is not answering.

## Anchors

| What | Where |
|---|---|
| The document | `docs/services/reference-scope.md` |
| Its membership check | `docs/README.md` (coverage map), `scripts/coverage_map.py` |
| The reasons, in the code | `feature/purchase-server-data/.../StaticPlanCatalog.kt`, `feature/esim-server-domain/.../EsimPorts.kt`, `feature/auth-server-domain/.../AuthPorts.kt`, `shared/server-common/.../DayFormat.kt` |
