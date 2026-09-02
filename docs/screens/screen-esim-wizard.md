---
id: screen-esim-wizard
title: Add an eSIM — the four-step flow
type: client_flow
platform: [jvm]
status: active
entry:
  jvm: "POST /api/v1/esim-wizard — the run's first screen; every later screen is the answer to POST /api/v1/esim-wizard/step"
parent_feature: feature-esim-install
calls_api:
  - endpoint-esim-wizard
source: feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/EsimWizardScreen.kt
---

# Flow: add an eSIM

> A `client_flow` rather than a `client_screen`: it is one tree redrawn per step, and the state
> machine lives on the server. The client owns no step, no ordering and no button meaning — **every
> button carries the action the server put on it**, and the client posts it back unchanged.
>
> Read out of the source on 2026-08-25.

## 0a. Code anchors

| What | File |
|---|---|
| The tree, per step | `feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/EsimWizardScreen.kt` |
| The step machine | `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/EsimWizardGraph.kt` |
| The rules and the refusal | `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/EsimWizardUseCases.kt` |
| The step ids and the meter | `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/EsimDomain.kt` |
| The action on every button | `feature/esim-shared-api/src/commonMain/kotlin/io/konekt/feature/esim/shared/api/EsimWizardStepAction.kt` |
| The QR renderer (client) | `client/src/commonMain/kotlin/io/konekt/client/render/EsimQrRenderer.kt` |
| The SM-DP+ mock | `feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/MockSmDpPlus.kt` |
| Tests | `feature/esim-server-data/src/test/kotlin/io/konekt/feature/esim/server/data/EsimWizardScreenTest.kt`, `.../EsimWizardRoutingTest.kt` |

## 0. Entry point and visibility

- **Entry point:** `POST /api/v1/esim-wizard`. There is no button anywhere in this build that starts
  it — no screen links here yet.
- **Shown when:** the caller holds a valid access token. A run belongs to one subscriber and
  somebody else's run answers `404`, not `403`.

## 1. The states of the machine

Four steps, in the order they are lived, from `EsimWizardSteps`: `check` → `confirm` → `activate` →
`done`. The tree is always a `column` with id `esim-wizard`, and it always opens with a `step_meter`
(id `esim-wizard-progress`) reading *n* of 4 — after the `screen_header` (§4.4), and unlabelled since
`B-115`: the header names the flow, and the meter draws the eyebrow `STEP n OF 4` itself.

| Step | What is on it | Forward button |
|---|---|---|
| `check` (1 of 4) | the heading *Before you start* (`esim-wizard-title`, `headline_medium`) and one `text`, `esim-wizard-check` — what an eSIM is and how long it takes | "Continue" (`Next`) — and the header's cross leaves: there is no step to go back to |
| `confirm` (2 of 4) | the heading *Get your eSIM* and one `text`, `esim-wizard-confirm` — that a profile will be requested, and that the code does not expire | "Get my eSIM" (`Next`) |
| `activate` (3 of 4) | `esim_qr` (`esim-wizard-qr`) in a `surface` card (`esim-wizard-qr-card`), plus a `text` telling the subscriber where to point the camera; no heading — the header already says *Scan or install* | "I have scanned it" (`Next`) |
| `done` (4 of 4) | the outcome (`B-115`): an `icon` check, the heading *Your eSIM is ready*, one sentence (`esim-wizard-done`), an `esim_card` with the ICCID and a sentence for its status, **and the QR again** in its card | "Done" (`Finish`) |
| refused | the step it was refused on, unchanged, with the refusal above the content as a `surface` card (`esim-wizard-refusal`) holding an amber `icon` and the sentence (`esim-wizard-refusal-text`) — the canvas's failed-check row, without the checklist (`B-115`) | the same controls |
| unknown step id | one `text` — "This step is not available in this version of the app. Update to continue." | "Continue" |

**The profile is issued on the way into `activate`**, not when the run starts, and exactly once.

## 2. API integration

| Call | Contract | Endpoint document |
| :--- | :--- | :--- |
| `POST /api/v1/esim-wizard` | `EsimWizardResource` | [endpoint-esim-wizard](../api/endpoint-esim-wizard.md) |
| `POST /api/v1/esim-wizard/step` | `EsimWizardStepAction` **as the body** | [endpoint-esim-wizard](../api/endpoint-esim-wizard.md) |

## 3. Initialisation

**Input parameters:** none. The run id is created by the server and travels back inside the actions on
the screen, which is why the record is persisted before the first screen is answered — a client
holding a run it cannot find would be holding a button that does nothing.

| Call | Case | Handling | State |
| :--- | :--- | :--- | :--- |
| `POST .../esim-wizard` | `200` | render the tree | step 1 of 4 |
| `POST .../step` | `200` | render the tree, whatever step it names | may be the **same** step, with a banner |
| `POST .../step` | `404` | the run is not this subscriber's, or does not exist | *not decided in this repository* |
| `POST .../step` | `422` | the body was not a step of this wizard | *not decided; it cannot happen to a client that posts back what it was given* |

## 4. UI elements

### 4.1. Step meter

- **Fields:** `current`, `total`, `label`. Both integers, because a "3 of 4" cannot be drawn from a
  sentence — and since `B-115` the renderer draws it: one equal segment per step across the width,
  `primary` for done and `primary_container` for the rest, with `STEP n OF total` under them as the
  eyebrow. The label, when sent, goes above; the wizard sends none.
- **`EsimWizardSteps.indexOf` is one-based and never answers zero** — an unknown id answers 1. A
  meter is chrome, and chrome that crashes a screen is worse than chrome that is wrong.

### 4.2. The QR block

- **Field:** `payload` — **the activation code itself, never an image.** An image needs a URL, a URL
  is fetched, and a fetched URL puts a credential into a query string and into somebody's access log.
  The client encodes the matrix locally.
- **`manualCodeText`:** the matching id in groups of four, for somebody typing it off their own
  screen. Not the whole `LPA:1$…$…` string: what a person is asked to type is the part that
  identifies the profile, and the rest is scheme and hostname they would only get wrong.
- **`captionText`:** "Stay on Wi-Fi. This takes up to a minute and finishes on its own."
- **The block sits in a `surface` card with `Copy activation code` under it** (`esim-wizard-qr-copy`,
  `B-115`): a `button` carrying `copy` with the whole LPA string. The client puts it on the
  clipboard and nothing about it reaches the server — see [operator-boundaries](../services/operator-boundaries.md).
- **A payload too large to encode leaves the typed code rather than throwing** —
  `EsimQrRendererTest`.
- **The code sits on a fixed light tile, in both themes** (`B-115`). The modules were black and the
  quiet zone was padding over the page — near-black in dark mode, so the dark frame was a code no
  camera could read. `EsimQrRenderer.QR_LIGHT` is the tile and it is not the theme's colour: it is
  there for a scanner, not for the eye. `EsimQrRendererTest` renders it on a painted dark page and
  reads the quiet zone's pixels.

### 4.3. The eSIM card, on the last step

- **Fields:** `label` ("New line"), `iccid`, `status`, `statusText`. The status word is the client's
  to branch on and the sentence is the subscriber's to read, so an unfamiliar word still gets a
  sentence: "This profile is in a state this version of the app does not describe."
- **Drawn as rows** since `B-115`: the label as the card's title, then `ICCID` — grouped in fours
  by the client, as a SIM tray prints it — and `Status` with a hairline between. `ready`, `installed`
  and `active` are the brand's colour; the rest, and any unknown word, the neutral text colour.
  `ready` used to be drawn in the amber the counters use for `low`, so good news read as a warning.

### 4.4. The header and the way forward

- **The tree opens with a `screen_header`** (`esim-wizard-header`, `B-115`): the title — *Install
  eSIM*, or *Scan or install* on `activate` — and the one back control. On `check` it `closes` with
  no action, so the shell's circle is a cross that leaves the flow; on `confirm` and `activate` it
  carries `EsimWizardStepAction(wizardId, Back)`; on `done` it `closes` with `Finish`, because going
  back from a finished flow re-issues nothing and confuses everything. There is no `Back` pill any
  more — the pill and the shell's chevron used to go different ways with nothing to tell them apart.
- **The way forward is a `surface` marked `pinned`** (`esim-wizard-controls`) holding one full-width
  button: `esim-wizard-next` (`Continue`, `Get my eSIM`, `I have scanned it`) or `esim-wizard-finish`
  (`Done`). The shell draws it above the bottom edge, outside the scroll. `activate` without a code
  pins nothing; the header is still the way back.
- Every control carries `EsimWizardStepAction(wizardId, transition)`. **Nothing about a step's
  meaning is assembled on the client.**

## 5. Navigation (summary)

- `Next` / `Back` / `Finish` ──▶ the same flow, redrawn. The flow has no exit of its own: nothing in
  this build navigates away from `done`, and "Done" ends the run rather than closing a screen.

## 6. Quirks

- **The slot limit is a screen, not a status code.** A device already holding eight profiles is
  refused on step one: the same step comes back, the meter still reads "1 of 4", and the banner
  carries the sentence. That frame is only reachable because the refusal travels in the view.
- **The copy in that banner is konekt's, not the canvas's.** The canvas names the failure — "the
  failure this flow actually hits in the field" — and supplies no sentence. Ours states the limit as
  a fact about the device and says what to do next, because "could not add eSIM" is what sends
  somebody to support. Written in `MockSmDpPlus`, asserted in `MockSmDpPlusTest`.
- **Back is never gated.** A subscriber who cannot go forward must still be able to go back; a refusal
  that blocked both would be a wizard with no exit except closing the application. Since `B-115` the
  way back is the header's circle rather than a pill, and it is there on every step.
- **The code is shown twice on purpose.** Somebody arrives at `done` having failed to scan — the
  camera would not focus, the sheet was dismissed — and a flow that takes the code away at the end
  hides the one thing still worth having.
- **The last step sends `Finish`, not `Next`.** wizard-core's resolver answers `null` on the last
  step, so a `Next` would stay put and the button would do nothing visible.
- **`WizardScreenComponent` is not used here.** kompot's wizard client half needs a `formId` naming a
  real `FormSchema`, and this flow has no form — so it takes the engine only and draws its own chrome
  from `step_meter`.
- **The ICCID is a real one: nineteen digits with a Luhn check digit.** A random nineteen-digit number
  would look identical on a screen and fail the first thing any real tool does with it.
