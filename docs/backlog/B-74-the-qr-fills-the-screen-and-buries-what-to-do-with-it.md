---
id: B-74
title: "The activation code fills the whole frame, so what to do with it is below the fold"
status: open
priority: P2
size: S
stage: stage-m4-proof
epic: feature-esim
---

# B-74 — A subscriber reaching the code sees only the code

The `activate` step, on a 393×852 phone frame, draws the QR at roughly 490 points. The step meter and
the code fit; **everything else is off-screen** — the caption, the manual code to type when the camera
will not read it, the instruction that says to open Settings, and both buttons.

So the screen that exists to tell somebody what to do shows them a square and nothing else. The
sentence explaining it is one flick away and nothing on the frame says so.

The last step has the same shape with the stakes reversed: `done` draws its banner —
**"Your eSIM is ready."** — above a second copy of the code, and by the time the flow gets there the
scroll is not at the top ([B-75](B-75-the-scroll-survives-a-wizard-step.md)), so the sentence a
subscriber is waiting for is above the fold instead of below it.

## Why nothing sees it

`AppFrame - App esim install` photographs step ONE. The activate step has no frame, so the harness
built for exactly this class of defect — "six frame-level defects found by a person and none by the
suite" — does not look at the one screen in this flow with a size problem.

The gallery cannot see it either, by construction: it sizes each frame to its content, so a QR that
overflows a phone is a taller picture rather than a clipped one.

## It is not only about reading

Writing [B-76](B-76-done-returns-to-the-first-step-instead-of-leaving.md)'s test made it concrete: a
Compose walk pressing the controls in order timed out at `I have scanned it`, because a press on a
node below the fold lands on nothing. The test now scrolls to each control first — which is what a
person does — but the fact that it had to is a measurement of how far down the control is.

## What to decide

Not simply "make it smaller". A code meant to be scanned by another phone's camera wants to be large,
and shrinking it to fit the instructions is trading the primary job for the secondary one. The options
worth weighing are a maximum height that keeps the first control on screen, moving the instruction
ABOVE the code, or both.

Whatever is chosen, the activate and done steps need frames, because a size decision that nothing
photographs is one that drifts back.

## Anchors

| What | Where |
|---|---|
| The step | `feature/esim-server-data/.../EsimWizardScreen.kt` (`activateContent`, `qrOf`) |
| The renderer | `client/src/commonMain/.../render/` |
| The frames that exist | `client/src/jvmTest/kotlin/io/konekt/screenshots/AppFrameScreenshots.kt` |
