---
id: B-57
title: "The catalogue has no filters, no per-unit price, one badge for every plan and no loading state"
status: done
priority: P2
size: M
stage: stage-m3-product
epic: feature-buy-package
---

# B-57 — The closest screen to the canvas, and what the remaining distance is made of

Section 02 and the served `plans-screen` agree on the important half: sold out is marked rather than
hidden, prices are right-aligned, badges draw. Five differences remain, and they are not one kind of
thing — which is why they are listed with what each actually costs.

| Canvas | Served | What it needs |
|---|---|---|
| Search "Country or region" + chips Popular / Europe / Asia / Global | one flat list | a query on the catalogue, and a zone on the plan |
| "Turkey" as the title, "10 GB · 30 days · 5G" beneath | one string `Turkey · 10 GB · 30 days` | a split on `plan_card`, and `5G` is data the domain lacks |
| `1 190 ₽` **and** `119 ₽ / GB` | price only | arithmetic the server can do today |
| per-plan note: "Installs in 2 min", "Top-up available", "restocks 28 Aug" | one badge, `On sale`, on all three | data per plan |
| a skeleton card as the fourth row | never sent | see below |
| explicit `Choose` button | the whole card is the action | a decision, not data |

- **The per-unit price is the one to take first**, and it is the only row above that needs no new
  data: quota and price are both on the plan, and the server already formats money. It is also the
  row that changes a decision — comparing a 5 GB and a 20 GB plan by total price is the comparison
  the canvas is trying to prevent.
- **The skeleton is not a gap in the client.** `SkeletonRenderer` is registered and works; the
  catalogue is static and answers immediately, so the server has no moment to send one. Drawing it
  anyway would be a loading state for a load that does not happen. It stays undrawn until something
  in this build is actually slow, and the canvas frame stands as the design for that day.
- **`5G`, "Installs in 2 min" and "restocks 28 Aug" are refused as strings.** A line asserting a
  network generation, a provisioning time or a restock date the domain does not hold is a mockup
  wearing the product's clothes — the same objection as the plan detail's network and hotspot rows.
- **Not covered:** the plan detail screen, which landed already.
- AC: a plan card carries its per-unit price, and a plan whose quota is not in the unit the price is
  quoted in does not draw one rather than drawing a wrong one.
- Anchors: `server/src/main/kotlin/io/konekt/screens/PlansScreen.kt`,
  `shared/components/src/commonMain/kotlin/io/konekt/components/PlanCardComponent.kt`.

## What landed

**Three of the six rows, and the first was not on the list.** The item priced the title split as "a
split on `plan_card`" — a change to the component. It was not: `PlanCardComponent` has carried
`title`, `quotaTexts`, `zoneText` and `badgeText` since it was written, and the SERVER was gluing
everything into the title and then sending the quota again underneath. The card said "Turkey · 10 GB
· 30 days" as a heading and "10 GB", "30 days once it starts" as lines below it. **The component
could always express the canvas's layout; nothing was using it.**

So the card's title is the place now — the way section 02 draws it — and `plan.title` keeps its full
form because the HISTORY needs it: a row from three months ago has no card under it to carry the
rest, and "Turkey" alone would not say which Turkey plan was bought.

**The quota gained minutes and messages.** The home bundle carries 300 minutes and 50 SMS and the
card listed "20 GB" and stopped, so a subscriber comparing it against a roaming package was comparing
gigabytes to gigabytes while one of the two also included calls. That is the comparison the card
exists to make, and it was getting it wrong by omission.

**The per-unit price**, which was the one row the item said needed no new data — and it needed a
decision instead. `Money` has no `div` deliberately: dividing money is a rounding decision, and one
made implicitly loses a kopeck per transaction. The objection does not apply here and the difference
is what justifies the function: **this figure is never charged.** It is never summed, held or
captured, and nobody's balance moves by it — it exists so a subscriber can see that Europe at $9 is
the DEAREST plan per gigabyte in this catalogue, which a column of totals actively hides.

Two things it got wrong first, both caught by a test written for them:

- **it divided by megabytes and wrote "GB"** — off by 1024, in the direction that makes every plan
  look free, with nothing on screen saying so. The price is scaled instead: `price × 1024 ÷ dataMb`,
  in the same base `UsageUnits` writes "20 GB" with, because two figures on one card computed in two
  bases disagree with each other for a living;
- **`(a/b + 1 if remainder) / 2` rounds an exact half DOWN**, because an exact half leaves no
  remainder to notice. `(2a + b) / 2b` is half-up. The case is five over two, which is the one a
  reviewer skips.

**One rendering decision moved.** `quotaTexts` drew one `Text` per entry, so adding minutes and
messages made the home card five lines tall and pushed the next card off the screen. Joined into one
subtitle in the RENDERER — the server still sends them apart, which is the right way round: they are
separate facts, and a client that wants a column would have nothing to make one from.

## Refused, with the reason

- **`5G`, "Installs in 2 min", "restocks 28 Aug"** — a line asserting a network generation, a
  provisioning time or a restock date the domain does not hold is a mockup wearing the product's
  clothes.
- **The skeleton.** `SkeletonRenderer` works; the catalogue is static and answers immediately, so
  there is no moment to send one. Drawing it anyway is a loading state for a load that does not
  happen.
- **Filters and search** stay open: they need a query on the catalogue, and the zone is on the plan
  but the search is not one line.
- **`zoneText` is now sent by nothing** and kept anyway. Taking a name off the wire is a coordinated
  release of both sides for a screen nobody is asking to change, and a deployment whose plans are not
  organised by place would want exactly that line back.
