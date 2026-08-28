---
id: B-74
title: "The activation code has no maximum size, so it grows with the window until the controls leave the screen"
status: done
priority: P2
size: S
stage: stage-m4-proof
epic: feature-esim
---

# B-74 — Reported off a desktop window, and the phone was never wrong

## What this item first said, and why it was wrong

> The `activate` step, on a 393×852 phone frame, draws the QR at roughly 490 points… everything else
> is off-screen.

The first half is false and I wrote it. The observation was real — the caption, the typed code, the
instruction and both buttons were below the fold — but it was made in a **desktop window about 740
points wide**, and attributed to a phone frame that had never been photographed.

The first thing this item actually produced was that frame. At 393×852 the code is about 230 points
and every part of the screen fits above the fold with room to spare. Nothing was wrong at the size the
product is for.

## What was wrong

`fillMaxWidth(0.7f)` has no maximum. Seven tenths of a phone is 275 points; seven tenths of whatever
window somebody drags is however wide they dragged it. At 900 the code was 630 points and everything
under it left the screen — far enough that a Compose walk pressing the controls in order hit nothing
and timed out, which is how [B-76](B-76-done-returns-to-the-first-step-instead-of-leaving.md)'s test
found it.

So the defect is a missing ceiling, not a wrong size.

## What was done

**The cap goes on the width the fraction is taken OF, and the order is the whole of it.**
`.fillMaxWidth(f).widthIn(max = x)` does not cap anything — measured, at 900 the code was still 630,
because `fillMaxWidth` resolves to an exact width that a later constraint does not shrink. Constraining
first and taking the fraction second gives `min(0.7 × parent, 0.7 × 400)`: a phone is below the cap
and untouched, and above it the code stops at 280 points.

**Three frames, and each answers something the others cannot.**

| Frame | What it is for |
|---|---|
| `App esim activate` | the step that hands over the code, at the size the product is for — and the frame that corrected this item |
| `App esim done` | the same question on the last step, which draws the code again under a banner and a card |
| `Esim activate wide` | 900×700, the only non-phone frame in the file: a size that is right at one width and absurd at another cannot be photographed at one width |

The phone goldens are byte-identical before and after the cap, which is the assertion that the fix
changed nothing where nothing was wrong.

## What was NOT done, deliberately

The item proposed moving the instruction above the code, or both. Neither is needed: with the ceiling
in place the instruction is on screen at both sizes, and reordering would move a sentence that is
correctly placed for the phone in order to fix a window that is now fixed.

## Anchors

| What | Where |
|---|---|
| The ceiling | `client/src/commonMain/kotlin/io/konekt/client/render/EsimQrRenderer.kt` |
| The frames | `client/src/jvmTest/kotlin/io/konekt/screenshots/AppFrameScreenshots.kt` |
| The step | `feature/esim-server-data/.../EsimWizardScreen.kt` (`activateContent`) |
