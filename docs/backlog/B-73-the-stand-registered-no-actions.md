---
id: B-73
title: "The stand's Json registered none of the three action modules, so every action it read was unknown"
status: done
priority: P1
size: XS
stage: stage-m4-proof
epic: feature-observability
---

# B-73 — A missing component module is loud; a missing action module is silent

`Stand.json` carried the component modules, the auth module and both form modules, under a comment
claiming it was *"the same module set the server assembles"*. It was not: `authActions`,
`esimActions` and `purchaseActions` were all absent, and had been for as long as the file existed.

Nothing failed, and the asymmetry is the point:

| What is missing | What happens |
|---|---|
| a COMPONENT module | the screen decodes to nothing — loud, and the suite has a test for it |
| an ACTION module | the button's `action` decodes to `UnknownAction` — the tree is fine |

So a test that pulls a control's action off a served tree gets `null` from its cast and concludes the
screen offers no control, which is indistinguishable from a server that drew none.

## How it surfaced

Writing [B-66](B-66-the-esim-qr-is-unreachable-through-the-app.md)'s scenario. The new walk read the
forward button's action to decide where to go next — which is what the client does, and what makes a
walk follow the server's graph instead of a second copy of it. It stood on step one for its whole
budget and reported that the activation code was never drawn, while the server was serving it. The
defect it was written for was real; this one would have hidden it.

## Fix

The three modules a CLIENT registers are now in `Stand.json` — not the five the server does: petich's
payloads and the dev screens are the server talking to itself. The comment says what the list is for
and records the asymmetry, so the next person adding an action knows why it belongs here.

## What is still not guarded

Nothing checks the two lists against each other. A fourth action module would be added to
`Application.kt` and forgotten here, and the symptom would again be a test that reads a control and
finds none. Worth a check that compares what the server registers against what the suite does —
filed as its own thought rather than done here, because it wants a way to enumerate a
`SerializersModule`, which is not free.

## Anchors

| What | Where |
|---|---|
| The suite's list | `e2e/src/test/kotlin/io/konekt/e2e/Stand.kt` |
| The server's list | `server/src/main/kotlin/io/konekt/Application.kt` |
| The walk that found it | `e2e/src/test/kotlin/io/konekt/e2e/EsimInstallScenarioTest.kt` |
