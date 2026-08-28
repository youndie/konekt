---
id: B-76
title: "Done finishes the install and lands the subscriber back on step one of a new one"
status: done
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

## What was done

`EsimInstall` answers a **`Destination`** rather than an address, and `Finish` is the one transition
that gets a different one. Everything else keeps refetching the wizard, which is right.

**The destination needed a third shape, and that is the part worth reading.** `Destination` carried one
boolean, `startsOver`, meaning both "clear the stack" and "the session changed". Neither existing
value fits a finished flow:

- `next` replaces only the TOP. The wizard sits on whatever opened it, so the order and the catalogue
  would stay underneath and the subscriber would land on **home with a back control on it** — the
  first defect this application was ever reported for.
- `startOver` clears the stack and also bumps `sessions`, which refetches the navigation graph. That
  would be a request to the server made because an eSIM was installed.

So the boolean became `Arrival.NEXT` / `FLOW_ENDED` / `SESSION_CHANGED`. Same lesson as
[B-69](B-69-held-is-not-installed.md): one value answering two questions is one that will be asked a
third.

**Guarded at two levels.** `BackControlTest` gains the arrival — a finished flow leaves no back
control — beside the existing boundary case and the positive control that keeps both honest.
`ClientAgainstStandTest` presses the real controls through the real chain to the end and asserts the
home screen arrives, that step one's copy is NOT on it, and that there is nothing to go back into.
Proved by mutation: restoring the old answer fails it.

**And it made B-74 concrete.** The walk kept timing out at the last control, because the activate
step's QR is about 490 points tall on a phone frame and its buttons are below the fold: a press
without a scroll lands on nothing. The test now scrolls to each control like a person, and that is a
measurement rather than an opinion — see
[B-74](B-74-the-qr-fills-the-screen-and-buries-what-to-do-with-it.md).

## Anchors

| What | Where |
|---|---|
| The client's answer | `client/src/commonMain/kotlin/io/konekt/client/app/EsimInstall.kt` |
| Why the GET starts a run | `feature/esim-server-domain/.../EsimWizardUseCases.kt` (`OpenEsimWizardUseCase`) |
| The control | `feature/esim-server-data/.../EsimWizardScreen.kt` (`controlsOf`, `DONE`) |
