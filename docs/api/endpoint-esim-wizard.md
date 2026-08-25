---
id: endpoint-esim-wizard
title: The eSIM install wizard
type: api_endpoints
status: active
services:
  - konekt-server
contract_source:
  - konekt:feature/esim-shared-api EsimWizardResource
  - konekt:feature/esim-shared-api EsimWizardStepAction (the request body of the step route)
parent_feature: feature-esim-install
---

# API: the eSIM install wizard

> Two routes, and both answer a **component tree** rather than a DTO — the flow is drawn on the
> server, one screen per step. See [screen-esim-wizard](../screens/screen-esim-wizard.md).
>
> Read out of the source on 2026-08-25.

## Routes — all of them, no exceptions

| Method and path | Auth tier | Answers | Purpose |
|---|---|---|---|
| `POST /api/v1/esim-wizard` | **user token** | `200` + a component tree | begin a run; the first screen is step 1 of 4 |
| `POST /api/v1/esim-wizard/step` | **user token** | `200` + a component tree | move an existing run; the body is the action the server put on the button |

Both are mounted by `esimWizardRoutes()`, in the `AuthTier.USER` group of `konektRoutes` in
`server/src/main/kotlin/io/konekt/Application.kt`. A run belongs to one subscriber, and the owner check is in the use case
beside the principal — answering **404 and not 403** for somebody else's run
(`AdvanceEsimWizardUseCase`, asserted by `EsimWizardRoutingTest`).

**The run's id is not in the path of the second route.** It travels inside the action, because that
action is the thing the server handed the client: a button on a step screen already carries which
wizard it belongs to, and putting the id in the path as well would be the same fact written twice
with nothing holding the two spellings together.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| both | `feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/EsimRouting.kt` |
| the screens | `feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/EsimWizardScreen.kt` |
| the step machine | `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/EsimWizardGraph.kt` |
| the rules, and where a refusal is decided | `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/EsimWizardUseCases.kt` |
| the SM-DP+ mock | `feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/MockSmDpPlus.kt` |

## Request and response bodies

**The step route's body is a `KompotAction`, not a request type of its own.** The client posts back,
unchanged, the action that was on the button it tapped:
`feature/esim-shared-api/src/commonMain/kotlin/io/konekt/feature/esim/shared/api/EsimWizardStepAction.kt`
— `@SerialName("esim_wizard_step")`, carrying `wizardId` and a `WizardTransition` from
`wizard-core`. Inventing a DTO here would mean the client translating an action into a request, which
is the one place a wire contract can drift without anything failing to compile.

It is read with `json.decodeKompotAction(call.receiveText())` and cast; anything else is refused.

Both routes answer with `respondKompotComponent`. The tree's own vocabulary — `step_meter`,
`banner`, `esim_qr`, `esim_card`, `button` — is
`shared/components/src/commonMain/kotlin/io/konekt/components/`.

## Errors

| Condition | Status | Body (`code` / `message`) |
|---|---|---|
| the body is not an `EsimWizardStepAction` (any other action, or unparseable) | `422` | `validation_failed` / `that is not a step of this wizard` (field `action`) |
| the run does not exist, **or belongs to somebody else** | `404` | `not_found` / `wizard was not found` |
| no token, or a token whose family was revoked | `401` | Ktor's challenge, not an `ApiError` |

**The eight-profile limit is not in this table, and that is the point of the whole feature.** A device
that already holds eight profiles is refused *inside* the answer: the same step comes back, the meter
still reads "1 of 4", and a `banner` on it carries the sentence. A slot limit answered with a `409`
is a status code with no screen behind it — the client has nothing to draw, the wizard is neither
here nor there, and the subscriber is told "conflict".

## Quirks

- **The gate runs before the transition, not inside the resolver.** wizard-core's engine models the
  graph and has no notion of refusal — a `Next` either moves or is the last step — so a rule that
  says "not from here, not yet" has to hold the session where it is. That is what makes the
  slot-limit frame reachable at all.
- **A profile is issued on the way into `activate`, exactly once.** The idempotency key is
  `EsimOrderDraft.issuedEsimId`, written in the same row as the step: a Back followed by a Next, a
  retried request or a double tap all find it done. A flag anywhere else could be true while the
  session said otherwise.
- **The last step sends `Finish`, not `Next`.** On the last step the resolver answers `null`, so a
  `Next` would stay put and the button would do nothing visible. `Finish` is also what marks the
  profile installed — and only if one was ever issued.
- **A finished run answers with itself rather than an error.** The client may still be holding its
  last screen, and answering 404 to the button on it would replace a finished wizard with a failure.
- **Nothing generates the action's registration.** kompot's KSP processor covers components; the
  `KompotAction` hierarchy is registered by hand, in `esimActionsSerializersModule`, and that module
  has to be in the application's `Json`. Leaving it out compiles, starts, and draws every screen —
  the failure is the decode on the way back in, on the one request the action exists for.
  `EsimWizardRoutingTest` covers it by posting the server's own action back.
- **An unrecognised step id draws a sentence rather than nothing.** It can only mean a row written by
  a build that knew more than this one.
