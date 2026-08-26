---
id: B-19
title: "Roaming: status, zones and packages bought before the trip"
status: done
priority: P2
size: M
stage: stage-m3-product
epic: feature-roaming
blocked_by: [B-08]
---

# B-19 — Roaming: status, zones and packages bought before the trip

A roaming package is bought at home and starts counting abroad. The canvas's plan detail says it
plainly — *"the plan starts counting on first connection, not now"* — and that sentence is the whole
feature: the product sells something that does nothing until it is used.

- **The decision and its reason.** A package carries a zone (`home` or a roaming zone) and an
  activation trigger, and the counter for a roaming package is created dormant. The purchase saga is
  the same saga; only the provisioning step differs, which is what keeps this from becoming a second
  purchase flow.
- The rejected alternative is a separate roaming order type. It duplicates the compensation logic,
  which is the part nobody wants two copies of.
- Not covered: real network attachment. Nothing observes a device landing anywhere; first use is
  simulated by the traffic simulator against the roaming counter.

- AC: a roaming package bought at home shows as bought and not yet counting. **Met.** `RoamingScenarioTest`
  buys Turkey through the real saga and reads `state: "dormant"`, `"10 GB ready"` and a caption with no
  end date off the real home screen.
- AC: the same package after simulated first use counts down and its expiry is dated from that moment.
  **Met.** The same test posts an arrival, waits for the card to leave `dormant`, and asserts the copy
  names both a start and an end. `RoamingPackageTest` holds the column-level version: bought in March,
  first used 100 days later, `expires_at == activated_at + 30 days`.
- Anchors: `server/src/main/kotlin/io/konekt/roaming/`,
  `feature/roaming-server-domain/`, `feature/roaming-server-data/`,
  `shared/db/src/main/resources/db/migration/V10__roaming_package.sql`.

Background: [design-app-canvas](../design/design-app-canvas.md) section 02.

## What it turned out to be

The catalogue was **already** entirely roaming — Turkey, Europe, the United States, each with a zone
and a validity written into its title — and every one of them was provisioned as an ordinary home
allowance that began counting the moment it was bought. A subscriber who bought Turkey in March had
spent it by April without leaving the country. So this item did not add roaming to the product; it made
the plans the product already sold behave the way the canvas describes.

## Deviations from the item as written

**A separate `roaming_package` table, not a dormant `usage_counter` row.** The item says "the counter
for a roaming package is created dormant". `usage_counter` is unique on `(subscriber_id, kind)`, so a
roaming data package would collide with the home one, and widening that key is an expand and a contract
a release apart. That alone would have been reason enough to postpone, but the better reason is that
the two are not the same thing: a package has a zone, an activation that may never happen, and an
expiry dated from that activation. Folding them together would put three nullable columns on the table
every screen reads and make "has this started" a question about NULL.

**First use is not the traffic simulator.** The item says first use is simulated by the simulator. It
cannot be: the simulator ticks every five seconds, so a package would start itself moments after being
bought and the state this whole feature exists to make visible — bought, not counting — would never be
on screen. So the simulator ticks only trips **already under way** (`RoamingPackages.travelling()`), and
starting one is a deliberate act: `POST /api/v1/dev/roaming/arrive`, mounted behind `DEV_SCREENS` like
every other demonstration control. In a real MVNO that event is a first-attach notification from the
network; here it is whoever is giving the demonstration.

**The catalogue gained a home plan, and had to.** Once every catalogue entry provisioned a dormant
roaming package, nothing in the product created a `usage_counter` at all — the home screen, the traffic
simulator, the low/exhausted copy and three e2e scenarios all rest on one existing. `home-20gb-30d` is
what makes the branch two-sided. It is also what an MVNO actually sells: a bundle for where you live and
packages for where you go.

## Decisions worth not re-litigating

**`CounterStates.DORMANT` rather than a new component.** What is on screen is a title, an amount, a
caption and a bar — exactly `UsageCounterCardComponent`. A `RoamingPackageCardComponent` would be the
same five fields under a different discriminator, and every client would need a renderer for it to draw
the identical card. What genuinely differs is one word, and the field was already an open string with a
documented fallback, so a client built before this word existed draws the ordinary card.

**`revoke` only removes a dormant package.** The compensation runs seconds after the grant, so in the
saga the distinction never arises. It is enforced anyway because a repository that can erase used data
is one edit away from being called from somewhere else — and a package the subscriber has already spent
from is not something a rollback may delete.

**The clamp is written before the subtraction**, the way `ExposedUsageCounters` had to learn it: a
10 MB request against 3 MB left must take 3, not go to minus seven. Covered at the boundary rather than
at a round number, because any figure outside that interval passes on the broken code.

## Proved by mutation, not by a green run

Both criteria went green on the first run, which is the case worth distrusting. Two mutations, each
restored afterwards:

| Mutation | Tests killed |
|---|---|
| `expiresAt` dated from `purchasedAt` instead of the activation | 3 of 9 |
| `grant` writes `activatedAt = purchasedAt` (package starts on purchase) | 6 of 9 |
