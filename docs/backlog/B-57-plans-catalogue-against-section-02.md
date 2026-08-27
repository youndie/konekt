---
id: B-57
title: "The catalogue has no filters, no per-unit price, one badge for every plan and no loading state"
status: open
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
