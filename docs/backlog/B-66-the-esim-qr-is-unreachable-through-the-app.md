---
id: B-66
title: "The activation code cannot be reached from the app: the resume path never carries the profile"
status: open
priority: P0
size: S
stage: stage-m4-proof
epic: feature-esim
---

# B-66 — One step, two paths, and the app takes the one that loses the profile

Bought a plan on the test contour, pressed **Install eSIM**, walked the wizard, and step three — the
step the whole flow exists for — said:

> We could not read your activation code. Go back and try again.

…above a button reading **I have scanned it**.

The comment on that branch says it is *"only reachable if the profile was issued and the row then
vanished"*. The row had not vanished. In the contour's database, for this subscriber:

```
 status | code_missing | code_len
 ready  | f            |       28
```

## The mechanism, measured on the wire rather than read

The same wizard, the same step, seconds apart:

| How the step is reached | What the server serves |
|---|---|
| `POST /api/v1/esim-wizard/step` | `esim-wizard-qr`, `esim-wizard-activate` |
| `GET /api/v1/screens/esim-install` | `esim-wizard-activate-missing` |

`AdvanceEsimWizardUseCase` builds its answer through a private `view(...)` that resolves the profile
from `record.session.draft.issuedEsimId`. `OpenEsimWizardUseCase` — the GET, the one that RESUMES an
unfinished run — returns `EsimWizardView(record)` with `esim` left at its default of `null`. Every
step it can draw is fine except the one that needs the profile.

**And the app only ever takes the GET.** `EsimInstall.addressFor` posts the step, checks the status,
discards the body and answers with the screen's address so the holder refetches — which its own
comment describes as the design: *"`/api/v1/screens/esim-install` OPENS it — resuming rather than
starting — so re-fetching after a step shows the step it moved to."* The design is right. The resume
path does not hold up its end.

## Why nothing caught it

`EsimWizardRoutingTest` posts steps and reads the POST's answer — the path that works. The GET is
exercised for the wizard's other steps, where a null profile changes nothing. So the one combination
that fails — **resume × ACTIVATE** — is the one no test forms.

## It is worse than one broken screen

After the purchase, Home's install banner is gone: it renders when `esimsHeld == 0`, and a profile
that is `ready` counts as held. So the eSIM is unreachable from the running product altogether —
no entry point on Home, and the wizard's own step refuses to show the code.

## Fix

`OpenEsimWizardUseCase` resolves the profile the way `AdvanceEsimWizardUseCase` does — the same
helper, not a second copy of it. Then a test that asserts the two paths agree, over every step
rather than over ACTIVATE: the step added next is the one that will disagree next.

Separately, the missing-code branch should not offer **I have scanned it**. A control to confirm
scanning something that is not on the screen is wrong whatever the reason the code is absent.

## Anchors

| What | Where |
|---|---|
| The path that loses it | `feature/esim-server-domain/.../EsimWizardUseCases.kt` (`OpenEsimWizardUseCase`) |
| The path that carries it | same file, the private `view(...)` |
| The branch it lands on | `feature/esim-server-data/.../EsimWizardScreen.kt` (`activateContent`) |
| The client that only takes the GET | `client/src/commonMain/kotlin/io/konekt/client/app/EsimInstall.kt` |
