---
id: B-71
title: "The way out is drawn with the same weight as the action, so the completed purchase has two primaries"
status: open
priority: P2
size: XS
stage: stage-m4-proof
epic: feature-client-shell
---

# B-71 — Install eSIM and Done, indistinguishable

On the completed purchase, **Install eSIM** and **Done** are both filled primaries of equal width and
colour. The screen has one action and one way out, and it draws them as two actions.

The product already gets this right one screen earlier: the confirmation pairs a filled **Pay $12**
with an outlined **Not now**. The difference is that `wayOut(...)` renders filled everywhere, which
is unremarkable on screens whose only control it is — the rejected screen, the top-up result — and
wrong on the one screen where it sits beside something a subscriber should actually press.

## Fix

`wayOut` takes the emphasis, or the completed branch asks for a lower one. Do not special-case it by
screen id: the next screen to gain a second control is the next one to get this wrong.

## Anchors

| What | Where |
|---|---|
| The pair | `feature/purchase-server-data/.../PurchaseResultScreen.kt` (`completed`, `wayOut`) |
| The pair that is right | same file, `awaitingConfirmation` |
