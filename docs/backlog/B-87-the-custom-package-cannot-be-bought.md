---
id: B-87
title: "The custom package form prices a package and cannot sell one, and nothing in the app leads to it"
status: done
priority: P0
size: M
stage: stage-m7-completeness
---

# B-87 — A form that answers a price and has no way to say yes

[B-20](B-20-custom-package-builder.md) built the builder: three fields, a patch that returns a price
computed on the server, and a balance check. Three things are missing and together they mean nobody
can buy anything:

1. **No submit.** `/api/v1/forms/custom-package` and `/patch` are the whole of it. There is no
   endpoint that turns a priced draft into an order, so the form's terminal state is a number.
2. **No way in.** The address is not in `Shell.graph()` and not in `KonektRoutes.bootstrap`, so no
   destination resolves to it.
3. **The client cannot decode it anyway** — `:client` does not depend on
   `feature:packages-shared-api`.

Its only callers are `e2e/.../CustomPackageScenarioTest.kt` and a stand test. Same shape as
[B-86](B-86-changing-tariff-has-no-screen.md), and this one is worse in one respect: the purchase
saga it would feed is already the best-covered path in the product, so the missing piece is
genuinely the wire and not the domain.

It also matters more than its size suggests, because this form is the only place in the build where
**kompot's form patching does real work**. A top-up form validates locally; this one asks the server
what a combination costs and redraws. That is the toolkit's own claim — *validation stays local,
and only a server-relevant change asks the backend for a patch* — and it is currently demonstrated
where only a test can see it.

- **The decision: add the submit, put the form behind the plans tab, and let it enter the purchase
  saga that already exists.** A custom package is a plan the catalogue did not write down; the
  interceptors do not need to know the difference.
- **The price is computed once, on the server, at submit** — not carried from the last patch. A
  price the client sends back is a price the client can argue with, which is the reason
  `operator-boundaries.md` gives for never handing over the table.
- **The rejected alternative is a slider.** kompot has no slider component, and
  `CustomPackageForm.kt` says so; three `select_input`s is what the toolkit has and the honest thing
  is to keep them and stop describing the feature as sliders anywhere.
- This item does **not** add promotional pricing — one tariff function, no campaign layer, per
  [B-20](B-20-custom-package-builder.md) — and does not move `CustomPackageTariff`'s constants out
  of Kotlin.

- AC: a subscriber reaches the builder from the plans tab, chooses quantities, sees the price change
  as the server recomputes it, submits, and gets the same purchase result screen a catalogue plan
  produces — including the refused and compensated branches.
- AC: an insufficient balance is refused with the reason and the control that acts on it (`Top up`),
  as [B-68](B-68-a-refused-purchase-never-says-why.md) requires.
- AC: the destination is in `Shell.graph()` and `:client` depends on `feature:packages-shared-api`.
- Anchors: `server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt`,
  `server/src/main/kotlin/io/konekt/packages/CustomPackageTariff.kt`,
  `feature/packages-shared-api/src/commonMain/kotlin/io/konekt/feature/packages/shared/api/CustomPackageApi.kt`,
  `server/src/main/kotlin/io/konekt/screens/Shell.kt`, `client/build.gradle.kts`.

## What was done

The three missing pieces, and the domain needed none of them.

**The submit.** `POST /api/v1/forms/custom-package` — the screen's own resource, the same arrangement
the amount form uses, so a sibling named `submit` cannot collide with anything. It reads the three
quantities out of the values a `submit_form` sends, **prices them here**, and starts the ordinary
purchase saga. Nothing in the request names a price.

**How a built package becomes a plan.** `CustomPackagePlans` decorates the catalogue and answers for
ids of the form `custom-5-100-50`; everything else it delegates. So the interceptors — the balance
check, the hold, the settlement, the entitlement, the compensation — do not know a built package from
a listed one, and every refusal and every screen the purchase already has works unchanged. **No row is
written**, because the package IS its three numbers and a table would be a second place for them to
live between the form and the order.

**Which makes the id untrusted input**, and that is the sharpest thing in this item. An id is a string
a caller can invent, so `custom-9999-0-0` reaches the parser exactly as a real one does. `find` does
not parse three numbers — it re-validates all three against the same step lists the form offers, and
answers `null` otherwise, which the purchase use case turns into a 404 rather than into an order for
9999 GB that every interceptor would then process correctly.

**The way in.** A banner on the plans tab, *after* the catalogue rather than before it: what is on
sale is what most people want, and a page that opens with a builder puts a configuration exercise in
front of a purchase. The address is in `Shell.graph()` as a **form**, and `:client` depends on
`feature:packages-shared-api` — which it did not, so it could not have decoded the address or the
submit even if something had pointed at one.

**The refusal is a screen.** The submit button is offered even when the package cannot be afforded,
which is the opposite of what the tariff cards do — and deliberately: the refusal lands on the order
screen, which names the reason and offers `Top up` ([B-68](B-68-a-refused-purchase-never-says-why.md)).
A button withheld on an affordability the client computed would be a rule the client owns, and the
whole reason the price is a patch is that it is not.

**A package of nothing** is the state the form opens on, so it stays resolvable and is refused at the
submit — two rules, and putting the second in the parser would have broken the first.

## Verified

- `CustomPackagePlansTest` — five cases, **proved by mutation**: dropping one step check accepts
  `custom-9999-0-0` and prices it at $14,998.50.
- `CustomPackageScenarioTest` gained three cases against a running stand — ordering from the banner
  the catalogue draws, the refused branch with its `Top up`, and the empty package answering 422. 34
  e2e tests, 0 failures.
- `./gradlew check` green.

### Two guards earned their keep while this was written

- **The stand's Json did not register `submit_form`.** Every submit button's action decoded to
  `UnknownAction` — which reads as a form with no way to submit it, indistinguishable from a server
  that drew none. The fourth time a hand-registered action has cost something here.
- **The `@Test` guard caught a case JUnit was not running.** One of the new scenarios ends in
  `assertNotNull`, which returns the value it checked, so the expression-bodied function was not void
  and therefore not a test. It is the exact failure `konekt.base` describes, found by it, the day it
  was written.

## What is deliberately not in scope

Promotional pricing — one tariff function, no campaign layer, per [B-20](B-20-custom-package-builder.md)
— and moving `CustomPackageTariff`'s constants out of Kotlin, a non-goal in
[reference-scope](../services/reference-scope.md). A slider is still not possible and the feature is
described nowhere as having one.

The documentation layers for this vertical are [B-93](B-93-two-verticals-have-screens-and-no-documents.md),
together with the tariff change's.

## Anchors

| What | Where |
|---|---|
| The submit | `server/src/main/kotlin/io/konekt/packages/CustomPackageRouting.kt` |
| A built package as a plan | `server/src/main/kotlin/io/konekt/packages/CustomPackagePlans.kt` |
| Where the catalogue is wrapped | `server/src/main/kotlin/io/konekt/Application.kt`, `feature/purchase-server-data/.../PurchaseModule.kt` |
| The way in | `server/src/main/kotlin/io/konekt/screens/PlansScreen.kt`, `Shell.kt` |
| The client's half | `client/build.gradle.kts`, `client/src/commonMain/kotlin/io/konekt/client/app/KonektRoutes.kt` |
