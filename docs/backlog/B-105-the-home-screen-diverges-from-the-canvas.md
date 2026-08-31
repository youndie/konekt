---
id: B-105
title: "The home screen draws three cards where the canvas draws one, and the balance block is laid out differently"
status: open
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

## Why this is not simply "restyle the cards"

**`usage_counter_card` is one counter.** The canvas's card is a group with a head and N rows, which is
not the same type with different padding — it is either a new dictionary type or a `surface` holding
rows that are not cards. Choosing between those is the work in this item, and it decides how much of
it is a client release:

- **a `surface` with plain rows** costs no wire type: `surface`, `text` and a progress row already
  exist, and the head is two texts in a row. The bars are the open question — there is no bare
  progress component, only the one inside `usage_counter_card`;
- **a new grouped type** carries the head and the rows as one thing, prices in one dictionary entry,
  and is a client release like any other.

**And the head needs a fact the server does not have.** `renews 12 Sep` is a renewal date; a plan here
is bought once and grants an allowance, and nothing renews. Either the head says something true — the
package's name and what it grants — or the product acquires renewals, which it does not have. This
item takes the first and records it, rather than drawing a date that means nothing.

## The decision

- **Bring the build to the canvas on the four things that need no new fact**: one card for the three
  allowances, the head carrying the package's name, full-width bars, and the number beside its label.
- **The phone moves beside the balance and the two buttons stop being equals** — `Top up` takes the
  row, `History` is quiet. Derived from what the block is for, not set per screen.
- **The renewal date is replaced by what is true**, and the absence of renewals is written down where
  a reader meets the card.
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
