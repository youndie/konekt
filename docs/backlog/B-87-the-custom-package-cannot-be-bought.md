---
id: B-87
title: "The custom package form prices a package and cannot sell one, and nothing in the app leads to it"
status: open
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
