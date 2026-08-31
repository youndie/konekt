---
id: B-105
title: "The home screen draws three cards where the canvas draws one, and the balance block is laid out differently"
status: done
priority: P2
size: M
stage: stage-m7-completeness
---

# B-105 — Six differences from a design that has been in the repository the whole time

Reported by somebody using the application, and every one of them is a place where the build and
`docs/design/konekt-esim-app.dc.html` disagree. The canvas is not being changed by this item; the
build is being brought to it.

| | the canvas draws | the build serves |
|---|---|---|
| the allowances | **one card** holding Data, Minutes and Messages | three separate `usage_counter_card`s |
| the card's head | the package's name left, **`renews 12 Sep` right** | no head at all |
| the bars | the card's full width | a bar per card, narrower |
| the number | beside its label, right-aligned in the row | inside its own card |
| the phone | **beside the balance**, in the same block | on its own line under the amount |
| Top up | `flex:1` — takes the row | one of two equal buttons |
| History | narrow, its own quieter ground | the same emphasis as Top up |

The last two are the shape [B-71](B-71-two-primary-buttons-on-the-completed-purchase.md) already named on
another screen: two controls of equal weight where one is the thing to press. It was fixed there per
screen; here it is the same defect in the balance block, which suggests the emphasis rule wants to be
derived rather than chosen each time.

## Half of this is already decided, and by an item that got further than this one did

**The grouping is [B-60](B-60-counter-copy-and-grouping.md)'s open remainder, not a new question.**
That item closed having settled the copy and left the grouping deliberately, with the analysis this
item should not repeat:

> The container it needed exists — `B-52` added `surface` — so nothing about the wire or the client is
> in the way. What is missing is a subscription: a purchase grants an allowance and that is the end of
> it, so there is no plan for the three counters to belong to and no renewal date to state. Drawing
> the title anyway would mean inventing both.

So: **no new dictionary type and no client release** for the grouping — `surface` holds it today. The
blocker was never the wire, it is that the head has nothing true to say.

**Which makes the head the whole of the remaining decision.** The canvas draws `Smart 20 · renews
12 Sep`; this build has no subscription, no plan the three counters belong to, and no renewal. Two
honest ways out, and this item takes the first:

1. **the head says what is true** — what the allowance came from, and nothing about renewal;
2. **the product acquires subscriptions**, which is a vertical and not a layout change.

**And one thing that must NOT be reopened.** B-60 decided that a counter states what is LEFT for all
three kinds, against the canvas, which writes `18 of 300` for the metered ones. That was a product
decision with a reason — two directions on one screen is how a subscriber misreads their own balance —
and moving the numbers into one card does not revisit it.

## What is genuinely new here

The balance block's internals. [B-52](B-52-the-balance-is-not-a-card.md) made it a card holding the
label, the amount, the number and both buttons — and said nothing about their arrangement, because
the defect it fixed was that there was no card at all. So the phone's position and the two buttons'
weights are open, and the buttons are [B-71](B-71-two-primary-buttons-on-the-completed-purchase.md)'s
shape a second time: two controls of equal weight where one is the thing to press.

## The decision

- **One `surface` for the three allowances**, with full-width bars and the number beside its label —
  all of it inside the vocabulary that exists, per B-60.
- **The phone moves beside the balance and the two buttons stop being equals** — `Top up` takes the
  row, `History` is quiet. Derived from what the block is for, not set per screen.
- **The head states what is true and no date.** The absence of renewals is already written in
  `design-app-canvas.md` and in B-60; this item adds nothing to that reasoning and does not overturn
  it.
- **Rejected: keeping three cards and restyling them.** The complaint is that three cards read as
  three unrelated things; padding does not fix that.

## Acceptance criteria

- AC: the home screen matches the canvas on the six rows above, checked against a photographed frame
  at **both widths** — a size that is right at one is absurd at the other, which is `B-74`'s lesson.
- AC: whichever shape is chosen, the dictionary and `konektWireNames` agree and the round-trip test
  covers any new type.
- AC: the recorded frames are re-recorded, and with at least the content the old ones carried — a
  regenerated golden of an emptier screen is one that has quietly stopped covering things.
- AC: no drawn value is invented. If the head cannot say a true date, it does not say a date.
- AC: `docs/screens/` says what the card is and what it holds, so the next change starts from the
  document rather than from the canvas.

## Anchors

| What | Where |
|---|---|
| The canvas, which has been here all along | `docs/design/konekt-esim-app.dc.html` |
| The screen as served | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` |
| The card that holds one counter | `shared/components/.../UsageCounterCardComponent`, `client/.../render/UsageCounterCardRenderer.kt` |
| The balance block | `HomeScreen.balanceCard` |
| The same two-primaries defect, fixed once already | `docs/backlog/B-71-two-primary-buttons-on-the-completed-purchase.md` |

## What was done

Checked against `docs/design/konekt-esim-app.dc.html` line by line, not from memory, and the served
tree brought to it:

| | now |
|---|---|
| the allowances | one `surface`, three rows inside it |
| the card's head | `Your allowance` left, `since 31 Aug` right |
| the bars | full width, 12 pt tall |
| the number | beside its label, on one baseline |
| the phone | right of the balance, same row |
| Top up | takes the row (`Weight`) |
| History | narrow **and quiet** — `ButtonEmphasis.QUIET` |

**No new dictionary type.** `usage_counter_card` gained `inline: Boolean`, which drops the card's own
ground and puts the label and the value on one baseline; the content is identical and only the chrome
differs, so a second component would have been a second thing to keep in step. Default false, because
the travel screen still draws each package as its own card.

**The head says what is true.** The canvas writes `Smart 20 · renews 12 Sep` and neither half can be
said: `UsageCounter` carries no reference to the plan that granted it — checked, not assumed — and
nothing renews. What IS available is `startedAt`, so the slot the canvas puts a date in carries a real
one. `B-60` reached this first and left the grouping for it.

## Two defects the screenshot found and the tree could not

Both were invisible in the served JSON and obvious in the picture, which is the argument for looking.

**The bar was filling with what had been SPENT while the number beside it said what was LEFT.** A
brand new 20 GB allowance drew an empty bar next to the words "20 GB left". `UsageCounter.progress` is
`used / limit` — a true number and the wrong one for this row — and `RoamingPackageCards` was already
filling with the remainder, so **one component was drawn in opposite directions by the two factories
that build it**. `B-60` settled that a counter states what is left because two directions on one
screen is how a subscriber misreads their own balance; the bar is part of that sentence.

**Width alone did not make the two buttons different.** They were the same colour and the eye had
nothing to land on. `ButtonEmphasis.QUIET` is the vocabulary `B-71` introduced for exactly this on the
purchase result — the second time the same defect has appeared on a different screen, which is worth
noting: the emphasis rule is still chosen per screen rather than derived.

## Verified

- Both recorded trees re-recorded from a running stand, reached through the product's own paths —
  including the issued-but-not-installed state, walked through the eSIM wizard rather than
  hand-edited — and each at least as rich as the frame it replaces.
- Screenshots re-recorded and **looked at**, not merely regenerated: the bar direction and the button
  colour are both things a green `viddikVerify` would have accepted.
- `:server:test`, `:client:jvmTest`, `build` and `make e2e` all green; `make check` green.
- The generated schema regenerated with `KONEKT_SPEC_RECORD=true`, **locally** — a record run on the
  replica writes files the one-way sync then reverts, so it reports success and changes nothing.

## Anchors

| What | Where |
|---|---|
| The screen | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` |
| The row form of the card | `shared/components/.../UsageCounterCardComponent.kt`, `client/.../UsageCounterCardRenderer.kt` |
| The bar's direction | `feature/usage-server-data/.../UsageCounterCards.kt` |
| The weight vocabulary | `server/src/main/kotlin/io/konekt/screens/Widths.kt` |
| The canvas it was checked against | `docs/design/konekt-esim-app.dc.html` |
