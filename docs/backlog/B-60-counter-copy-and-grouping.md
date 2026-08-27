---
id: B-60
title: "The canvas states a counter as used-of-total and groups the three under the plan; we do neither"
status: done
priority: P2
size: M
stage: stage-m3-product
epic: feature-usage-counters
---

# B-60 — Two disagreements about the same three cards, and one of them is a product decision

**The value reads the other way.** The canvas writes `18 of 300` and `0 of 50`; the served card
writes `249 min left` and `0 SMS left`. Both are true and they point in opposite directions — one
answers "how much have I used", the other "how much is left". The canvas is not consistent either:
its data row says `15,8 GB left` while its minutes and SMS rows say `of`. That may be deliberate
(a metered thing you are close to exhausting reads better as a fraction) or it may be a drawing
detail. **It is a question for the designer, not a defect to fix silently** — and the reason it is
worth asking is that a subscriber who misreads the direction misreads their remaining balance.

**The three are loose.** The canvas puts all three counters INSIDE a card titled `Smart 20` with
`renews 12 Sep`; the served tree emits three sibling cards with no plan named anywhere. This one is
not a layout choice: this build has no subscription entity at all. A purchase grants an allowance and
that is the end of it — there is no plan a counter belongs to, and no renewal date to state, because
nothing renews. Drawing a title over the three would mean inventing both.

- **The decision and its reason.** Ask about the copy; the grouping waits on a subscription. Adding
  a subscription to make three cards look right is the tail wagging the dog — but it is also the
  thing standing between this product and the most ordinary question an MVNO subscriber has, which
  is when their plan renews and for how much.
- **Not covered:** the container needed to draw the group at all — [B-52](B-52-the-balance-is-not-a-card.md)
  is the same missing piece, and whichever way that goes decides this one's mechanism.
- AC: either the copy matches the canvas per counter kind, or the canvas records that this build
  states remainders and why.
- Anchors: `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/`,
  `docs/design/design-app-canvas.md`.

## What was decided

**A counter states what is LEFT, for all three kinds.** The canvas is not consistent — data reads
`15,8 GB left` and the metered kinds read `18 of 300` — and matching it frame by frame would put two
directions on one screen. A subscriber who misreads the direction misreads their remaining balance, so
picking one is worth more than matching each drawing. Recorded in
[design-app-canvas](../design/design-app-canvas.md); no code changed.

**The grouping stays open and is now purely a domain question.** The container it needed exists —
[B-52](B-52-the-balance-is-not-a-card.md) added `surface` — so nothing about the wire or the client is
in the way. What is missing is a subscription: a purchase grants an allowance and that is the end of
it, so there is no plan for the three counters to belong to and no renewal date to state. Drawing the
title anyway would mean inventing both.
