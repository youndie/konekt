---
id: feature-custom-package
title: Build your own package — three quantities, a server-computed price, and the same purchase saga
type: feature
status: active
owner: unassigned
involved_services:
  - konekt-server
  - konekt-client
client_entries:
  - screen-custom-package
api:
  # No hand-written endpoint document for these three routes. They are in the generated one, which is
  # derived from the routing tree and cannot be wrong about paths, methods or auth tiers; what it
  # cannot say is why, which is what this file is for.
  - api-openapi
tags: [packages, forms, form-patch, purchase, saga]
---

# The custom package builder

## 1. Overview

The catalogue sells fixed plans. This sells a package a subscriber assembles: some data, some minutes,
some messages, priced by one tariff function on the server, and bought through the **same purchase
saga** as anything else.

Two things make it worth more than its size. It is the only place in this build where **kompot's form
patching does real work** — the top-up form validates locally, and this one asks the server what a
combination costs and redraws nothing. And a custom package is **a plan the catalogue did not write
down**: the id carries the three quantities, the catalogue answers for it, and the interceptors do not
know the difference.

`B-20` built the form and left it unable to sell anything: no submit endpoint, no place in the route
graph, and `:client` without the contract. Its only callers were two tests until `B-87`.

## 2. Business rules

| Rule | Where it is enforced |
|---|---|
| Quantities come from fixed step lists | `CustomPackageTariff.DATA_GB_STEPS` and its two siblings. The client picks from the same lists the server prices |
| A quantity outside the steps is **refused, not rounded** | rounding would charge for a package nobody chose; the form, the patch and the plan lookup all check |
| The price is the server's, always | `CustomPackageTariff.priceOf`, evaluated on open, on every patch, and **again at submit**. Nothing in a request names a price |
| A package of nothing cannot be ordered | `CustomPackagePlans.requireSomethingChosen` → 422. It is still a valid state to *open* on |
| An unaffordable package is refused by the saga, on a screen | the balance check is a purchase interceptor; the submit does not pre-check, so the refusal arrives with a reason and a `Top up` control |
| The id is untrusted input | `CustomPackagePlans.find` re-validates all three quantities rather than parsing three numbers — `custom-9999-0-0` resolves to nothing and is a 404 |

## 3. Code anchors

| What | File |
|---|---|
| The schema, the tree, the patch | `server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt` |
| The price | `server/src/main/kotlin/io/konekt/packages/CustomPackageTariff.kt` |
| Open, patch, submit | `server/src/main/kotlin/io/konekt/packages/CustomPackageRouting.kt` |
| A built package as a plan | `server/src/main/kotlin/io/konekt/packages/CustomPackagePlans.kt` |
| Where the catalogue is wrapped | `server/src/main/kotlin/io/konekt/Application.kt` |
| The way in | `server/src/main/kotlin/io/konekt/screens/PlansScreen.kt`, `Shell.kt` |
| The field ids and addresses | `feature/packages-shared-api/src/commonMain/kotlin/io/konekt/feature/packages/shared/api/CustomPackageApi.kt` |

## 4. Scenarios (BDD / test cases)

### Scenario: a package is built and bought

```gherkin
Given a signed-in subscriber with money on their account
When they open the plans tab
Then a banner offers to build their own package, navigating to app://custom-package
When they open the builder and choose 5 GB, 100 minutes and 50 messages
And they press "Order this package"
Then they land on an order screen
And the order is awaiting confirmation
And its price is the tariff's, computed on the server
When they confirm it
Then the order completes
```

**Automated:** `e2e CustomPackageScenarioTest`

### Scenario: choosing a size reprices without redrawing

```gherkin
Given the builder is open
When a quantity changes
Then the server answers a FormPatch and nothing else
And it updates price and balance
And no field loses what it holds
```

**Automated:** `e2e CustomPackageScenarioTest`

### Scenario: a package beyond the balance is refused on a screen

```gherkin
Given a subscriber with an empty balance
When they order a package that costs more than nothing
Then they land on the order screen
And it says nothing was charged
And it offers "Top up"
```

**Automated:** `e2e CustomPackageScenarioTest`

### Scenario: an id nobody was offered is not an order

```gherkin
Given a purchase requested for the plan id "custom-9999-0-0"
Then the catalogue resolves nothing for it
And the purchase is refused with 404
```

**Automated:** `server CustomPackagePlansTest`

### Scenario: a package of nothing

```gherkin
Given the builder opened with three zeros
Then the form is valid and the price is nothing
When it is submitted
Then the answer is 422
```

**Automated:** `e2e CustomPackageScenarioTest`, `server CustomPackagePlansTest`

## 5. Wire format

A `KompotFormResponse` — a `FormSchema` and a component tree — on open; a `FormPatch` on every change;
a `navigate` on submit. The submit action is kompot's own `submit_form`, which the client resolves to
this form's address through `KonektRoutes.submits`.

**No new component type and no new action.** The three quantities are `select_input`s and the two
computed values are `read_only_field`s bound by `fieldId` — the latter only possible since
youndie/kompot#89.

## 6. Out of scope

* **Promotional pricing.** One tariff function, no campaign layer (`B-20`).
* **Sliders.** The wire has none, and nothing here describes the feature as having them.
* **Moving the tariff's constants out of Kotlin**, a non-goal in
  [reference-scope](../services/reference-scope.md).

## 7. Quirks

- **No row is written for a built package.** The package *is* its three numbers, and a table would be
  a second place for them to live between the form and the order — which is also what makes the id
  untrusted input and the re-validation in `find` load-bearing.
- **The catalogue is wrapped rather than replaced.** `purchaseModule` takes the catalogue as a
  parameter so the composition root can decorate it; a second `single<PlanCatalog>` in the root would
  resolve to whichever Koin saw last.
- **The stand's Json did not know `submit_form`.** Every submit button's action decoded to
  `UnknownAction` — which reads as a form with no way to submit it, and is indistinguishable from a
  server that drew none. The fourth time a hand-registered action has cost something here.
