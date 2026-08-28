---
id: B-71
title: "The way out is drawn with the same weight as the action, so the completed purchase has two primaries"
status: done
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

## What was done

**No branch decides any more.** `wayOut` is gone; `controls(wayOutId, wayOutText, vararg actions)`
takes whatever else the state has to press and derives the weight from it — full when the way out is
the only control, quiet when it is not. Every one of the six states goes through it.

That is the difference between this and the one-line version. Four of the five states had it right by
each branch choosing correctly, which is precisely the arrangement that produced the fifth: the
confirmation asked for `quiet` by hand, the rejection had grown its own `emphasisFor`, and the
completed purchase asked for nothing. Both hand-written choices are deleted — the rule gives them the
same answer.

**Guarded over every state**, with a vacuity assertion that some state actually drew a second control,
since "exactly one full-weight button" is satisfied trivially by screens that only ever have one. A
second test says WHICH one is full weight, because demoting both would also pass the first. Proved by
mutation: making the way out unconditionally primary fails all three of the tests that touch it, and
names `COMPLETED`.

**The recording was stale**, so `Gallery_Purchase_result.png` still photographed two filled buttons.
Re-recorded from the stand — with the same five movements the previous frame carried, so the
regenerated golden is no poorer than the one it replaces.

## Anchors

| What | Where |
|---|---|
| The pair | `feature/purchase-server-data/.../PurchaseResultScreen.kt` (`completed`, `wayOut`) |
| The pair that is right | same file, `awaitingConfirmation` |
