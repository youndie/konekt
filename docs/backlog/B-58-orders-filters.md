---
id: B-58
title: "Orders has no filters and an active order shows nothing about what is left of it"
status: done
priority: P2
size: M
stage: stage-m3-product
epic: feature-buy-package
---

# B-58 — Section 05 asks a list to answer two questions, and it answers one

The served history is a `paginated_list` of `order_row`, each carrying title, reference, date, amount
and status. Section 05 draws the same list with two things this one does not have:

- **Filter chips: All / Active / Refunded.** They are a query parameter and a row of controls, and
  the endpoint already pages — so this is a `status` filter on the cursor query rather than anything
  structural.
- **Per-row state for an active order: "15,8 GB left · 18 days", and a `Top up` action.** This is the
  expensive half: the row is built from the entitlement and the remaining quota lives in the usage
  feature's counters. A history repository reaching into usage's tables is how two features become
  one — so the composition happens where it happens for the home screen, in `:server`, or the row
  carries a field the routing fills.

- **The decision and its reason.** Take the chips first and separately. They need no cross-feature
  read, they change what the screen is FOR — a subscriber looking for a refund is not scrolling — and
  they are the half that will still be right after `Top up` on a row moves somewhere else.
- **The `Top up` on a row is not this product's top-up.** The canvas means topping up THAT PACKAGE's
  data, which is a different verb from raising a balance — there is no such saga. It is a separate
  item the day somebody wants it, not a button wired to the balance form.
- **Not covered:** whether a top-up appears in this list at all — [B-53](B-53-history-excludes-top-ups.md).
- AC: choosing Refunded shows the compensated orders and nothing else, and the count of rows across
  the three chips is not more than All.
- Anchors:
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/HistoryScreen.kt`,
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedHistoryRepository.kt`.

## What landed

**The chips, and nothing else from the item.** All / Active / Refunded as a query parameter on the
screen and on its page, a row of buttons above the list, and the server saying which slice is open —
the same argument the bottom bar's `selected` makes: a client deciding it by reading its own address
would be a second opinion, and the two disagree the first time an address gains a parameter.

**No new wire type.** `ButtonEmphasis` already distinguishes the chosen control from the rest, so a
`chip` would have been a twelfth name for something the vocabulary can say.

**The filter travels with the cursor**, and that is the bug the shape prevents: a keyset cursor is a
position in a FILTERED list. Asking for the next page without it walks the unfiltered list from this
boundary and appends rows the subscriber just narrowed away. There is a test that pages a slice with
twice as many excluded rows interleaved in time.

**An unknown word is the whole list, not a 400.** The filter arrives in a URL and a URL is something
anybody can hand somebody; for a list being searched, showing too much is the right way to be wrong.

**Three things this found that were not the task:**

1. **`quiet` had never drawn as anything.** The word travelled from the day `ButtonEmphasis` existed
   and `KonektDesignSystem` gave it the shape and nothing else — with a comment saying emphasis waits
   for "a canvas frame". Nobody could see it because the only screen sending it, the eSIM wizard, has
   no primary button beside it; three chips in a row had two of them lying. Section 05 is that frame,
   and the old comment's warning was honoured: naming a container without naming a foreground is how
   "Cancel" becomes unreadable, so all three are set.
2. **A filtered tab stopped being a tab.** Each chip is a `navigate` to `app://orders?filter=…`, and
   the shell compared the deeplink whole — so three chips pressed in turn became three presses of back
   before leaving the screen. Matched before the query now.
3. **`FrameDifference` was measuring more than geometry**, and `BrandSwitchTest` caught it within a
   minute of the quiet button losing its fill. See below — it is the most interesting thing here.

## The guard was right and its model was approximate

`compareFrames` read `a.alpha != b.alpha` and called that geometry. Exact for a filled shape, wrong
for antialiased text on a transparent ground: **Skia gamma-corrects glyph coverage by the text's
luminance**, so the same word, same place, same size draws different edge alphas in two colours.
Brand A's dark teal and brand B's orange reported **171 pixels of movement** on the word "Back".

Diagnosed by measurement rather than by argument: pinning the text colour and changing nothing else
took it to zero. The comparison now asks whether a pixel is painted AT ALL — a threshold rather than
a tolerance, because "is this painted" is a fact about the drawing while "are these close enough" is a
number that drifts until somebody widens it. Both of the guard's positive controls still fire, which
is what says the sensitivity worth having survived.

**Not done, and refused rather than deferred:** the canvas's `Top up` on an active row. It means
topping up THAT PACKAGE's data, which is a different verb from raising a balance and has no saga —
wiring it to the balance form would be a button that does something other than what it says. The
per-row remaining stays out too: it needs the usage feature's counters, and a history repository
reaching into them is how two features become one.
