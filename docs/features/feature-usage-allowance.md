---
id: feature-usage-allowance
title: Allowances — what is left, how long it will last, and watching it move
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
tags: [usage, counters, realtime, sse, simulator]
---

# Allowances and what is left of them

## 1. Overview

A counter is what a subscriber has left, and of what: data, minutes, messages. A completed purchase
grants one; using the line spends it; the home screen shows all of them, and the screen **moves while
the subscriber is looking at it** — a frame arrives over SSE and one card is replaced by id.

Two things make the card worth more than a number. The **state** — normal, low, exhausted — is the
server's judgement, because "low" depends on the subscriber's rate of use. And in the low state the
copy is a projection and an offer: *"Minutes run out in about two days at your current pace. A
100-minute add-on costs $4."*

## 2. Business rules

* **A counter is not money.** It is a plain `Long`, because minor units and a currency have no meaning
  for megabytes. The two look alike and behave differently, which is the argument for two types.
* A counter never goes below zero, and the clamp is **in the database**: two decrements arriving
  together both pass a read-then-check, and a negative allowance is a screen that says minus four
  hundred megabytes.
* Only a purchase grants an allowance. Nothing else does.
* A rolled-back purchase takes its allowance back — clamped at zero, because some of it may already
  have been spent.
* **Low is a tenth or less**, and it is the server that says so.
* The projection is `remaining / (used per day so far)`, and it is **null rather than zero** when the
  question cannot be answered: nothing spent yet, or an allowance that started this instant.
* Every string on the card is built on the server. `progress` is the exception, because it is geometry
  rather than language, and it is **null when there is no ceiling** — a bar cannot be drawn for an
  unlimited allowance and a full one would say the opposite of what is true.
* A live update **names the node it replaces**: `counter-<kind>`, derived from the counter. A
  generated id is a frame that arrives and changes nothing.

## 3. Flow

**Granting:** the purchase saga's EXECUTION step calls `UsageGrants.grantPlanAllowance`. The port
lives in the *usage* domain and is named from the purchase one, because what an allowance is made of
is this feature's business and a purchase only knows it bought a plan.

**Spending, today:** nothing real produces traffic, so `TrafficChain` does — and it publishes to the
broker rather than writing counters directly, because the path exercised has to be the one a real
integration would use: broker → consumer → counter → realtime → screen. A simulator that wrote the
database would prove none of it and would be a second place counters are decremented.

1. `TrafficSimulator` publishes a `usage` event every 5 seconds, 25 MB per subscriber **who has
   something to spend** — publishing for anyone else produces events the consumer correctly ignores,
   and a simulator producing only ignored events looks exactly like one that is not running.
2. `UsageConsumer` reads the topic, decrements through `ConsumeUsageUseCase`, and pushes the rebuilt
   card through `ComponentBroadcaster`.
3. The subscriber's open SSE stream carries the frame; the client replaces the node.

The whole chain is **off unless `SIMULATE_TRAFFIC=true`**: it spends real counters with fictional
traffic, and a deployment that forgot the switch must not be one that quietly empties its
subscribers' allowances.

## 4. Code anchors

| Service | Code |
|---|---|
| konekt-server | `feature/usage-server-domain/src/main/kotlin/io/konekt/feature/usage/server/domain/UsageCounter.kt` — the counter, the projection, the ports |
| konekt-server | `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/` — the repository, the card builder, the unit formatting, the add-on price list |
| konekt-server | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` — where the balance and the counters are composed into one screen |
| konekt-server | `server/src/main/kotlin/io/konekt/realtime/RealtimeRouting.kt` — the SSE route and `ComponentBroadcaster` |
| konekt-broker | `server/src/main/kotlin/io/konekt/mocks/traffic/` — the simulator, the consumer, and the chain that starts both |
| konekt-client | `client/src/commonMain/kotlin/io/konekt/client/render/UsageCounterCardRenderer.kt`, `.../realtime/SseRealtimeSource.kt` |

## 5. Scenarios (BDD / test cases)

### Scenario: the balance is stated and every counter gets a card
* **Given:** a subscriber with a balance and three counters
* **When:** they open the home screen
* **Then:** the tree carries the formatted balance and one card per counter, in the enum's order
* **Automated:** `HomeScreenTest`

### Scenario: a subscriber with no plan is told so and given somewhere to go
* **Given:** a subscriber who has bought nothing
* **When:** they open the home screen
* **Then:** a banner says no plan is active, with a "See plans" action — not an empty column
* **Automated:** `HomeScreenTest`

### Scenario: a balance that could not be read is left out rather than drawn as zero
* **Given:** a subscriber whose account row cannot be read
* **When:** the screen is built
* **Then:** the balance block is absent entirely
* **Automated:** `HomeScreenTest`

### Scenario: the low state changes the copy and not only the colour
* **Given:** a counter with under a tenth left, and an add-on on the price list
* **When:** the card is built
* **Then:** the caption projects when it runs out and states what the add-on costs
* **And:** an ordinary counter carries **no caption at all**
* **Automated:** `HomeScreenTest`, `UsageCounterCardsTest`

### Scenario: a counter measured before any time has passed does not project
* **Given:** an allowance granted this instant, or one nothing has been spent from
* **When:** the caption is built
* **Then:** it falls back to "Running low — under a tenth …" rather than inventing a date
* **Automated:** `UsageCounterCardsTest`, `UsageCounterTest`

### Scenario: an exhausted counter says so plainly and still offers the way out
* **Given:** a counter at zero
* **When:** the card is built
* **Then:** "You have used all of your data." plus the add-on's price
* **Automated:** `UsageCounterCardsTest`

### Scenario: low is a tenth, and the boundary is on the low side
* **Given:** a counter with exactly a tenth left
* **When:** its state is read
* **Then:** it is `low`
* **Automated:** `UsageCounterTest`

### Scenario: the id is derived from the counter so a live update can name it
* **Given:** any counter
* **When:** its card is built
* **Then:** the id is `counter-<kind>` and not a generated one
* **Automated:** `UsageCounterCardsTest`

### Scenario: traffic published to the broker moves the counter and pushes the new card
* **Given:** the chain running against a real broker and a real database
* **When:** the simulator publishes usage for a subscriber who holds counters
* **Then:** the counter goes down and a frame carrying the rebuilt card is broadcast for that
  subscriber
* **Automated:** `TrafficChainTest`

### Scenario: a counter is floored at zero rather than going negative
* **Given:** a counter with less left than one tick spends
* **When:** the tick is applied
* **Then:** it stops at zero
* **Automated:** `TrafficChainTest`

### Scenario: usage for a subscriber who bought nothing is ignored rather than failing
* **Given:** an event for a subscriber with no counter of that kind
* **When:** the consumer applies it
* **Then:** nothing happens and nothing throws
* **Automated:** `TrafficChainTest`

### Scenario: a component pushed for one subscriber reaches that subscriber and nobody else
* **Given:** two subscribers with open streams
* **When:** a card is pushed for one
* **Then:** only their stream receives it, and a client that goes away is forgotten
* **Automated:** `RealtimeStreamTest`

### Scenario: a counter that moves reaches an open stream, across five processes
* **Given:** the stand, with the simulator on
* **When:** a subscriber with an allowance holds a stream open
* **Then:** an update arrives on it
* **Automated:** `LiveUpdateScenarioTest`

### Scenario: a subscriber buys the add-on the card offers them
* **Given:** a low counter whose caption names a price
* **When:** they try to buy it
* **Then:** **they cannot.** The card carries no action, and nothing sells add-ons. Manual, and there
  is nothing to run.

### Scenario: a cold start shows a value that a live update has already superseded
* **Given:** a screen whose cached copy predates an update that was applied and discarded
* **When:** the application is started again
* **Then:** **unanswered.** This is `B-18`, and the hypothesis on record is "stale for exactly one
  request".

## 6. Out of scope

* Selling add-ons or anything else that tops a counter up.
* Any real usage: no OCS, no CDRs. The only producer is the simulator, and the only kind it produces
  is `data`.
* A history of decrements. The projection is a mean over the life of the allowance precisely because
  there is no such table, and there would be no second reader for one.
* Roaming zones and per-zone allowances (`B-19`).

## 7. Quirks

- **The projection is a mean, not a trend, and the copy is deliberately vague about it.** Somebody who
  watched a film on day one and nothing since is told two days when they have a fortnight. The word
  "about" is doing real work, and `UsageUnits.approximately` refuses to print "1.8 days".
- **The counter's plural verb is chosen by hand** — "Data runs out", "Minutes run out". Getting it
  wrong reads as a machine wrote the screen, which on a backend-driven product is exactly the
  impression the copy exists to avoid.
- **One instance behind two interfaces.** `ExposedUsageCounters` implements both `UsageCounters` and
  `UsageGrants`, and two `single { }` blocks would build two of it — harmless today and exactly the
  sort of thing that stops being harmless when one of them caches.
- **This feature shipped complete, tested, and installed by nothing.** Five imports sat in
  `Application.kt` with no use beneath them: no counters in the graph, `LoadCountersUseCase` never
  constructed, and a completed purchase granting no allowance. Every test passed, because each built
  what it needed by hand. `FeatureModulesReachTheGraphTest` reads the composition root as text
  because of this.
- **`TrafficChain` exists because neither half was ever started.** The simulator and the consumer were
  written and covered end to end against a real broker and constructed by nothing outside that test.
  A chain that is tested and never started passes every acceptance criterion about being tested.
- **The chain resumes from where the broker is now, not from zero.** booblik keeps no consumer
  offsets, so a starting point has to be chosen; replaying a day of simulated usage on every restart
  would empty every counter in the product. Right here, wrong for anything real.
- **The SSE channel is unbounded.** The broadcaster drops on a full channel, so a bound would silently
  lose updates for a client that is merely slow.
