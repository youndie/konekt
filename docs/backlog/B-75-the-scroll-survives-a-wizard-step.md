---
id: B-75
title: "A wizard step keeps the previous step's scroll, so the new screen opens part-way down"
status: done
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

## What was done

**The holder already knew, and the answer is not the step id.** The question is not "did the tree
change" but WHY it was fetched, and `reloads` is exactly that: it is bumped in one place — the action
path, after a press answered with somewhere to be, including the case where that somewhere is where
we already are. A live update does not touch it, and neither does the refetch after a stream gap.

So the scroll state is `key(current, reloads) { rememberScrollState() }`: a new address or a press
gives a new one, an update leaves it alone. `key` rather than `remember(...)` so what is inside stays
the saveable state the toolkit gives.

No knowledge of wizards was needed, which matters — a holder that knew what a step was would be this
application's holder rather than a reusable one.

**Both halves are asserted, and the second one took two goes to become real.**
`ScrollAcrossScreensTest` scrolls, then presses; and scrolls, then fires a stream restart. Proved by
mutation in both directions: removing the key fails the first, keying on the fetched screen as well
fails the second.

That second mutation initially passed, and the reason is worth keeping. The fake answered an
IDENTICAL tree every time, so `key(..., screen)` saw no change and reset nothing — the guard was
vacuous against exactly the implementation it exists to refuse. A refetch after a gap exists to bring
back changed data, so the fake now answers differently each time.

**And the assertions are about what is DISPLAYED, not what exists.** A `Column` with `verticalScroll`
composes every child whatever its position, so `onAllNodesWithText` finds a row that left the screen
long ago. The first version of the test failed on its own precondition for that reason.

## Anchors

| What | Where |
|---|---|
| The refetch | `client/src/commonMain/kotlin/io/konekt/client/app/EsimInstall.kt` |
| The holder | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt` |
