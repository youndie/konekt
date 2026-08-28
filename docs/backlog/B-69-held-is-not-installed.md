---
id: B-69
title: "\"1 eSIM installed\" over a number that counts profiles held, installed or not"
status: open
priority: P1
size: S
stage: stage-m4-proof
epic: feature-esim-lifecycle
---

# B-69 — The sentence claims more than the number knows

Profile, immediately after a purchase and with nothing installed on anything:

> 1 eSIM installed

The row behind it is `status = ready`. The model distinguishes the two perfectly well — there is an
`INSTALLED` status and a `markInstalled`, whose own comment insists that *"Installed is not active"*
— so the distinction is made everywhere except in the sentence a subscriber reads.

The number is `countHeldBy`, and its query says what it is for:

> A TERMINATED profile does not occupy a slot, and that is the whole content of this query.

It counts SLOTS. The parameter is even called `esimsHeld`. Only the copy calls it installed.

## The same number drives a control, so this is not only wording

Home renders its install banner when `esimsHeld == 0`. A subscriber who has bought a profile and
installed nothing therefore loses the banner — the entry point to the very wizard they still need.
Together with [B-66](B-66-the-esim-qr-is-unreachable-through-the-app.md) that leaves the eSIM
unreachable from the running app: no banner, and a wizard that will not show the code.

## Fix

Say held, or count installed — not one labelled as the other. The banner wants the second: it should
appear while anything is bought and not yet installed, which is exactly the state that currently
hides it.

## Anchors

| What | Where |
|---|---|
| The sentence | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` (`esimLine`) |
| The number | `feature/esim-server-data/.../ExposedEsimRepository.kt` (`countHeldBy`) |
| The banner that reads it | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` |
