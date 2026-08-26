---
id: feature-roaming
title: Roaming — a package bought at home that starts when the trip does
type: feature
status: active
owner: unassigned
involved_services:
  - konekt-server
  - konekt-broker
  - konekt-client
client_entries:
  - screen-home
api:
  - endpoint-home
tags: [roaming, zones, packages, saga, simulator]
---

# Roaming packages

## 1. Overview

The catalogue sells packages for places: Turkey, Europe, the United States. A subscriber buys one at
home, before the trip, and it does nothing — it sits on the home screen saying it is ready and has not
started. The first time data is used in that zone it starts counting, and only then does it acquire an
end date, thirty days from that moment rather than from the purchase.

The canvas says it in one line: *"the plan starts counting on first connection, not now"*. That
sentence is the whole feature, and everything below is what it costs to be true.

It is the same purchase saga as any other plan — same validation, same hold, same settlement, same
compensation. Exactly one step differs.

## 2. Business rules

* **A plan carries a zone.** `home` is the absence of roaming rather than somewhere anyone travels to;
  it is what a plan carries when it grants an ordinary allowance.
* **A roaming package is granted dormant, always.** There is no code path that grants a started one,
  because there is no moment at which paying for a package should start it.
* **Its expiry is dated from activation**, and it is `NULL` until then. A package bought in March for a
  trip in June is not a package that expired in April.
* **The validity is copied off the plan at purchase**, not read back from the catalogue at activation.
  A package must last what it was sold as, not what the plan says three months later.
* **Time passing does not start it.** A 30-day package bought 90 days ago has still not started.
* **Activation and first use are one event.** There is no `activate` that can be called without
  spending anything — an API that separates them permits one without the other.
* **A started package is spent before a dormant one.** Two live packages for a zone run one after the
  other rather than in parallel; starting a second clock early would strand the remainder of the first.
* **A package never goes below zero**, and the clamp is written before the subtraction — the lesson
  `usage_counter` paid for.
* **A rollback removes a dormant package and leaves a started one.** Spent bytes are not something a
  compensation may erase.
* **One package per order.** `roaming_package.order_id` is unique, so a retried saga step grants one.

## 3. Code anchors

| What | Where |
|---|---|
| The package, the zones, the ports | `feature/roaming-server-domain/src/main/kotlin/io/konekt/feature/roaming/server/domain/RoamingDomain.kt` |
| The repository, including the activation | `feature/roaming-server-data/src/main/kotlin/io/konekt/feature/roaming/server/data/ExposedRoamingPackages.kt` |
| The table | `shared/db/src/main/resources/db/migration/V10__roaming_package.sql` |
| The one step that differs | `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseInterceptors.kt` (`grantAllowance` / `revokeAllowance`) |
| The card and the zone names | `server/src/main/kotlin/io/konekt/roaming/` |
| The arrival control | `server/src/main/kotlin/io/konekt/roaming/dev/ArriveRouting.kt` |
| Routing a zoned usage event | `server/src/main/kotlin/io/konekt/mocks/traffic/UsageConsumer.kt` |
| The in-memory repository for tests | `feature/roaming-server-domain/src/testFixtures/kotlin/io/konekt/feature/roaming/server/domain/InMemoryRoamingPackages.kt` |

## 4. Scenarios (BDD / test cases)

### Scenario: a package bought at home is ready and not counting

```gherkin
Given a subscriber with money on their account
When they buy the Turkey package and confirm it
Then the home screen shows a card titled "Turkey data"
And its state is "dormant"
And it says "10 GB ready"
And its caption says it starts on first connection
And the caption names no end date
```

**Automated:** `e2e RoamingScenarioTest`, `feature/roaming-server-data RoamingPackageTest`

### Scenario: time at home neither starts it nor expires it

```gherkin
Given a 30-day Turkey package bought 90 days ago and never used
Then it is still dormant
And it is still usable
And the traffic simulator has nothing to tick for that subscriber
```

**Automated:** `feature/roaming-server-data RoamingPackageTest`

### Scenario: first use starts it and dates the expiry from then

```gherkin
Given a dormant Turkey package bought 100 days ago
When data is used in the Turkey zone
Then the package counts down
And its activation is recorded as that moment
And its expiry is 30 days after that moment, not after the purchase
```

**Automated:** `e2e RoamingScenarioTest`, `feature/roaming-server-data RoamingPackageTest`

### Scenario: a second use does not restart the clock

```gherkin
Given a Turkey package first used yesterday
When data is used in the Turkey zone again
Then it counts down further
And its expiry is still 30 days after the first use
```

**Automated:** `feature/roaming-server-data RoamingPackageTest`

### Scenario: a rolled-back purchase takes a dormant package back

```gherkin
Given a purchase that granted a dormant package
When the saga compensates
Then the package is gone
But a package that had already been used is left alone
```

**Automated:** `feature/roaming-server-data RoamingPackageTest`

## 5. Wire format

A roaming package is drawn with `usage_counter_card`, the same component as a home counter. The only
new word on the wire is `state: "dormant"`, added to `CounterStates`. `state` was already an open
string with a documented fallback, so a client built before this word existed draws the ordinary card —
wrong in its colour and right in its numbers.

## 6. Out of scope

* **Real network attachment.** Nothing observes a device landing anywhere. Starting a package is
  `POST /api/v1/dev/roaming/arrive`, mounted only where `DEV_SCREENS` is set; in a real MVNO that event
  is a first-attach notification from the network.
* Per-zone pricing, zone discovery, or a screen listing which countries a zone contains.
* Anything at all for `minutes` or `messages` abroad. The only kind that exists in a zone is `data`.
* Extending, pausing or refunding a started package.

## 7. Quirks

- **The catalogue was already entirely roaming and behaved as if it were not.** Every plan carried a
  country and a validity in its title and was provisioned as a home allowance that started counting on
  purchase. A subscriber who bought Turkey in March had spent it by April without leaving the country.
  This feature did not add roaming; it made the plans the product already sold behave as described.
- **`home-20gb-30d` exists because the branch has two sides.** With every catalogue entry provisioning
  a dormant package, nothing in the product created a `usage_counter` at all — and the home screen, the
  simulator, the low/exhausted copy and three e2e scenarios all rest on one existing.
- **The simulator deliberately will not start a package.** It ticks only trips already under way
  (`RoamingPackages.travelling()`). A simulator that ticked every package would start each one about
  five seconds after purchase, and the state this feature exists to make visible would never be on
  screen long enough for anyone to see it.
- **A separate table rather than a dormant `usage_counter` row.** `usage_counter` is unique on
  `(subscriber_id, kind)`, so a roaming data package would have collided with the home one. The better
  reason is that a package has a zone, an activation that may never happen, and an expiry dated from
  it — three nullable columns on the table every screen reads, and "has this started" becoming a
  question about NULL.
- **The simulator omits `zone` for home traffic rather than writing `"home"`.** Its events stay
  byte-identical to the ones it produced before roaming existed, so the consumer's default is exercised
  by the ordinary path and not only by a test.
- **Dates are formatted in UTC**, and this product has no notion of where a subscriber is. The device's
  zone would be a client-side format, which D15 forbids; the roaming zone's would be wrong for the half
  of the trip spent packing. An end date that reads a day off in Auckland is a smaller defect than two
  screens disagreeing about it.
- **A zone with no name renders as its code.** `RoamingZoneNames` falls back to the raw string, so a
  zone added to the catalogue and not to that map produces a card that says "us" and works, rather than
  a 500.
