---
id: B-76
title: "Done finishes the install and lands the subscriber back on step one of a new one"
status: open
priority: P1
size: S
stage: stage-m4-proof
epic: feature-esim
---

# B-76 — The button that ends the flow starts it again

Pressing **Done** on the last step of the install wizard finishes the run and shows step one of a
**new** wizard: "An eSIM is a profile your phone downloads… / Continue".

A subscriber who has just installed their eSIM and pressed the button that says they are finished is
told about eSIMs from the beginning.

## The server is right and the client is not

`EsimInstall.addressFor` answers with the install screen's address for EVERY `EsimWizardStepAction`,
`Finish` included, so the holder refetches — and `GET /api/v1/screens/esim-install` on a subscriber
with no unfinished run correctly **starts one**. That behaviour is deliberate and documented in
`OpenEsimWizardUseCase`: somebody who installed one line and came back to install another must get a
fresh run rather than a finished screen.

The comment on `EsimInstall` even records the consequence — *"the next `GET` starts a new one rather
than answering a finished screen… worth knowing before somebody reads it as a bug"* — but what it
describes is a subscriber ARRIVING again, not one who has just pressed Done. `Finish` is the one
transition after which the flow should be left rather than refetched.

## A second cost, smaller and quieter

Every press writes a wizard session row, because the refetch takes the create branch. A subscriber who
presses Done twice has two abandoned runs.

## Fix

`Finish` answers a destination rather than the wizard's own address — home, or the order the install
came from. Everything else keeps refetching.

Then a client-level test: press the last control and assert the screen is no longer the wizard.
`ClientAgainstStandTest` already drives this flow to step two and is the natural place.

## Anchors

| What | Where |
|---|---|
| The client's answer | `client/src/commonMain/kotlin/io/konekt/client/app/EsimInstall.kt` |
| Why the GET starts a run | `feature/esim-server-domain/.../EsimWizardUseCases.kt` (`OpenEsimWizardUseCase`) |
| The control | `feature/esim-server-data/.../EsimWizardScreen.kt` (`controlsOf`, `DONE`) |
