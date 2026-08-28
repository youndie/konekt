---
id: B-75
title: "A wizard step keeps the previous step's scroll, so the new screen opens part-way down"
status: open
priority: P2
size: S
stage: stage-m4-proof
epic: feature-client-shell
---

# B-75 — The screen changed and the scroll did not

Pressing **I have scanned it** at the bottom of the `activate` step moves the wizard to `done` — and
the new screen appears scrolled to roughly where the old one was. What lands in view is the middle of
the second QR; the banner at the top, **"Your eSIM is ready."**, is off-screen.

Measured on the contour with the desktop client: after the step, reaching the top took a deliberate
scroll of about 500 points.

## Why it happens

The address does not change. `EsimInstall` posts the transition and answers with the SAME screen
address, so the holder refetches rather than navigating — which is the design, and the right one — and
the scroll container is therefore never recreated. A screen that replaces its content underneath a
scroll position keeps that position.

This is not specific to the wizard. Any place a served tree is replaced at the same address has the
same shape; the wizard is where it is visible because its steps are tall.

## What it costs

The one sentence a subscriber has been waiting for since they paid is the one they do not see. Nothing
is broken and nothing reports it — the tree is right, the golden is right, and the screen is right if
you scroll.

## What to decide

Whether "the tree at this address changed" should reset the scroll, and how the holder can tell that
apart from a refetch that arrives with the same content — a live update, a filter chip, a poll.
Resetting on every refetch would jump a subscriber to the top when a counter ticks, which is worse.

The wizard has a signal the others do not: the step id.

## Anchors

| What | Where |
|---|---|
| The refetch | `client/src/commonMain/kotlin/io/konekt/client/app/EsimInstall.kt` |
| The holder | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt` |
