---
id: screen-custom-package
title: The custom package builder — the one form where a patch does real work
type: client_screen
platform: [jvm, android, ios]
status: active
entry:
  jvm: "GET /api/v1/forms/custom-package — a KompotFormResponse: a schema and a tree; there is no client-side screen class"
parent_feature: feature-custom-package
calls_api:
  - api-openapi
source: server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt
---

# Screen: build your own package

> The only place in this build where **kompot's form patching does real work**. A top-up form
> validates locally; this one asks the server what a combination costs and redraws nothing. That is
> the toolkit's own claim — *validation stays local, and only a server-relevant change asks the
> backend for a patch* — and until `B-87` it was demonstrated where only a test could see it.
>
> Read out of the source on 2026-08-29.

## 0a. Code anchors

| What | File |
|---|---|
| The schema and the tree | `server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt` |
| The price | `server/src/main/kotlin/io/konekt/packages/CustomPackageTariff.kt` |
| The routes: open, patch, submit | `server/src/main/kotlin/io/konekt/packages/CustomPackageRouting.kt` |
| A built package as a plan | `server/src/main/kotlin/io/konekt/packages/CustomPackagePlans.kt` |
| The way in | `server/src/main/kotlin/io/konekt/screens/PlansScreen.kt` |
| The field ids and the addresses | `feature/packages-shared-api/src/commonMain/kotlin/io/konekt/feature/packages/shared/api/CustomPackageApi.kt` |

## 0. Entry point and visibility

`app://custom-package`, reached from a banner at the **bottom** of the plans tab — after the
catalogue, not before it. What is on sale is what most people want; building your own is the answer
for the ones the list does not fit, and a page that opens with a builder puts a configuration exercise
in front of a purchase.

Behind the user tier: the form shows the caller's own balance and prices against it.

## 1. Screen states

| State | What is on screen |
|---|---|
| opened | three quantity selects at their first step, a price of nothing, the balance, and **Order this package** |
| repriced | the same tree with `price` and `balance` updated by a patch. Nothing is redrawn and no field loses what it holds |
| cannot be afforded | the balance field carries a helper line saying so, and a patch that finds it unaffordable **focuses** that field. The submit is still offered |
| refused | not a state of this screen: the submit answers a `navigate` to the order screen, which names the reason and offers **Top up** |

**The submit is offered even when the package cannot be afforded**, which is the opposite of what the
tariff cards do — and deliberately. A card is withheld because the server would refuse the press;
here the refusal is the *point*: it lands on the order screen with a reason and a control that acts on
it (`B-68`). A button withheld on an affordability the client computed would also be a rule the client
owns, and the whole reason the price is a patch is that it is not.

## 2. API integration

Three addresses. `GET /api/v1/forms/custom-package` answers a `KompotFormResponse` — a `FormSchema`
plus a component tree. `POST …/patch` answers a `FormPatch` and **nothing else**: no schema, no tree.
`POST` on the form's own address is the submit, which answers a `navigate` to the order screen.

The submit **prices the package again on the server**. A price carried from the last patch is a price
the client can argue with, which is the reason `operator-boundaries` gives for never handing over the
table.

## 3. UI elements, top to bottom

The title; `Data, GB`, `Minutes`, `Messages` as `select_input`s over the tariff's own step lists; the
price and the balance as `read_only_field`s bound to the controller; and the submit.

**Three selects and not sliders.** kompot's standard field set is text, amount, checkbox,
autocomplete and selection — there is no slider and no numeric range — so a quantity is a choice from
a list. That suits a tariff, which sells packages rather than arbitrary numbers, and nothing in this
repository describes the feature as having sliders.

## 4. Navigation (summary)

`plans → app://custom-package → (submit_form) → app://order/{orderId}` — the same order screen a
catalogue plan produces, including its refused and compensated branches.

## 5. Quirks

- **The computed fields were thrown out by the conformance kit once.** `price` and `balance` were
  declared and not rendered, because the only non-editable display was not bound to the controller —
  so a computed value could be declared or displayed and not both. Filed as youndie/kompot#89 and
  fixed in `0.33.0`, where `read_only_field` takes an optional `fieldId`.
- **A package of nothing is a real state.** The form opens on three zeros priced at nothing, so it has
  to stay openable — and ordering one answers **422**. Two rules, and putting the second in the price
  function would have broken the first.
- **The submit is not walked by the conformance kit**, and it is declared as unwalkable with its
  reason: a walk that pressed it would order a package on every run.
