---
id: feature-esim-install
title: Adding an eSIM — a four-step flow that can be told no
type: feature
status: active
owner: unassigned
involved_services:
  - konekt-server
client_entries:
  - screen-esim-wizard
api:
  - endpoint-esim-wizard
tags: [esim, wizard, sm-dp+, mock]
---

# Adding an eSIM

## 1. Overview

A subscriber asks for an eSIM profile and installs it by scanning a QR code. Four steps: what an eSIM
is, a confirmation, the code, and a last screen that shows the code again.

The flow exists as much for its **failure** as for its success. The design canvas names the
eight-profile device limit as "the failure this flow actually hits in the field", and a refusal that
came back as a status code would be a wizard that is neither here nor there — so a refusal is
**part of the answer**: the same step comes back with a banner on it, and the step meter still reads
"1 of 4".

## 2. Business rules

* A run belongs to one subscriber. Somebody else's answers `404`, never `403`.
* The rule about how many profiles a device may hold belongs to the **manager**, and the count of
  what has been issued belongs to **us**. `SmDpPlus.capacityFor(profilesHeld)` is that split.
* A **terminated** profile does not occupy a slot: the difference between a subscriber who has used
  eight and one who has ever had eight.
* A profile is issued **on the way into `activate`**, and exactly once however many times the step is
  entered.
* **Back is never gated.** A subscriber who cannot go forward must still be able to go back.
* `Finish` on the last step is the subscriber saying the profile is installed, and that is the only
  thing that marks it so. A run abandoned before `activate` has nothing to mark.
* A finished run is **read-only**, not an error.
* The activation code is shown twice, on `activate` and on `done`, deliberately.

## 3. Flow

`POST /api/v1/esim-wizard` creates a run — persisted **before** the first screen is answered, because
the run's id travels back inside the actions on that screen. Every later move is
`POST /api/v1/esim-wizard/step` carrying the action the server put on the button.

Inside `AdvanceEsimWizardUseCase`, in this order:

1. find the run and check the owner;
2. if it is finished, answer with itself;
3. **if the transition moves forward, evaluate the gate — before the transition, not inside the
   resolver.** wizard-core's engine has no notion of refusal, so a rule that says "not from here, not
   yet" has to hold the session where it is;
4. transition;
5. if the new step is `activate` and nothing has been issued yet, ask the SM-DP+ and create the
   profile;
6. if the run is now finished, mark the profile installed;
7. save, and answer with the screen.

## 4. Code anchors

| Service | Code |
|---|---|
| konekt-server | `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/` — the graph, the use cases, the ports, the refusal type |
| konekt-server | `feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/` — the routes, the screens, the repositories, the SM-DP+ mock |
| konekt-server | `feature/esim-shared-api/src/commonMain/kotlin/io/konekt/feature/esim/shared/api/` — the resource and the one action konekt adds to the wire |
| konekt-client | `client/src/commonMain/kotlin/io/konekt/client/render/EsimQrRenderer.kt` — the QR is encoded on the device |

## 5. Scenarios (BDD / test cases)

### Scenario: the whole install ends with a QR carrying the code that was issued
* **Given:** a signed-in subscriber with no profiles
* **When:** they start the wizard and step through to `activate`
* **Then:** the screen carries an `esim_qr` whose payload is exactly the activation code that was
  issued — an `LPA:1$rsp.konekt.io$…` string
* **Automated:** `EsimWizardRoutingTest`

### Scenario: a full device is refused on step one and the wizard does not advance
* **Given:** a subscriber already holding eight profiles
* **When:** they press Continue on step one
* **Then:** the answer is `200`, the step is still `check`, the meter still reads 1 of 4, and a banner
  says the device holds eight profiles and what to do about it
* **And:** nothing was written and no profile was requested
* **Automated:** `EsimWizardRoutingTest`, `AdvanceEsimWizardUseCaseTest`, and the copy itself in
  `MockSmDpPlusTest`

### Scenario: a terminated profile does not hold a slot
* **Given:** a subscriber with eight profiles, one of them terminated
* **When:** they start a new run and step forward
* **Then:** it advances
* **Automated:** `EsimWizardRoutingTest`

### Scenario: a profile is issued exactly once, however many times the step is entered
* **Given:** a run that has reached `activate`
* **When:** the subscriber goes Back and then Next again
* **Then:** there is still exactly one profile, and it is the same one
* **Automated:** `AdvanceEsimWizardUseCaseTest`

### Scenario: finishing the last step marks the profile installed
* **Given:** a run on `done` with a profile
* **When:** `Finish` is sent
* **Then:** the profile's status becomes `installed`
* **And:** a run abandoned before a profile existed marks nothing
* **Automated:** `AdvanceEsimWizardUseCaseTest`

### Scenario: a next on the last step stays put and does not finish the run
* **Given:** a run on `done`
* **When:** `Next` is sent instead of `Finish`
* **Then:** the run stays on `done` and is not finished — which is why the button sends `Finish`
* **Automated:** `EsimWizardGraphTest`

### Scenario: somebody else's run answers 404 rather than 403
* **Given:** a run belonging to another subscriber
* **When:** a step is posted for it
* **Then:** `404` with `not_found` / `wizard was not found`
* **Automated:** `EsimWizardRoutingTest`, `AdvanceEsimWizardUseCaseTest`

### Scenario: a body that is not a step of this wizard is refused, not mistaken for one
* **Given:** a request carrying some other `KompotAction`
* **When:** it is posted to the step route
* **Then:** `422` with `that is not a step of this wizard`
* **Automated:** `EsimWizardRoutingTest`

### Scenario: the action the server sends is the action the server accepts
* **Given:** the screen the wizard just built
* **When:** the action on its forward button is posted back unchanged
* **Then:** it decodes and the wizard advances — which is what proves the hand-written action
  registration is in the application's `Json`
* **Automated:** `EsimWizardRoutingTest` — the case `the action that comes back is the one the wizard would accept next`

### Scenario: the ICCID is a real ICCID
* **Given:** an issued profile
* **When:** its ICCID is checked
* **Then:** nineteen digits, passing Luhn, starting `89` — the telecom industry identifier. (The
  code's own prefix constant is `8944`; the test asserts `89`.)
* **Automated:** `MockSmDpPlusTest`

### Scenario: a subscriber reaches this flow from somewhere in the application
* **Given:** the running client
* **When:** they look for a way to add an eSIM
* **Then:** **there is none.** No screen links to `POST /api/v1/esim-wizard`, and no test covers this
  because there is nothing to cover.

## 6. Out of scope

* A real SM-DP+. Nothing leaves the process; the profile is issued by a mock that can refuse.
* Any eSIM lifecycle beyond `ready` → `installed`. `EsimStatuses` names six states and this flow
  writes two of them; suspension and termination have no route.
* Transferring a profile to another device, and the `esim_transfer_widget` the canvas uses as the
  example of a component this build does not know.

## 7. Quirks

- **The refusal is not an exception.** `EsimWizardView` carries it, so the same step can come back
  with a reason on it. That is the whole reason the gate is in the use case rather than in the graph.
- **The copy in the slot-limit banner is konekt's own.** The canvas names the failure and supplies no
  sentence. It states the limit as a fact about the device and says what to do next.
- **The idempotency key is in the draft**, written in the same row as the step. A flag anywhere else
  could be true while the session said otherwise.
- **`WizardScreenComponent` is not used.** It needs a `formId` naming a real `FormSchema`, and this
  flow has no form — so the feature takes `wizard-core`'s engine and draws its own chrome.
- **Nothing generates the registration for `EsimWizardStepAction`.** It is hand-written into
  `esimActionsSerializersModule` and must be in the application's `Json`; omitting it compiles,
  starts, and draws every screen, and fails the decode on the one request the action exists for.
- **The QR is never an image.** The wire carries the code and the client encodes the matrix — an
  image needs a URL, and a fetched URL puts a credential into a query string and an access log.
- **The step meter counts from one and never reads zero**, even for a step id it does not know: chrome
  that crashes a screen is worse than chrome that is wrong.
