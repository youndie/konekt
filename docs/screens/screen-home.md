---
id: screen-home
title: Home — balance and allowances
type: client_screen
platform: [jvm]
status: active
entry:
  jvm: "GET /api/v1/screens/home — the tree is built by the server; the client has no navigation graph yet"
parent_feature: feature-usage-allowance
calls_api:
  - endpoint-home
source: server/src/main/kotlin/io/konekt/screens/HomeScreen.kt
---

# Screen: home

> **This screen has no client-side class.** It is a component tree the server builds and the client
> renders; the "screen states" below are branches in `HomeScreen.build`, not fields of a UI state
> object, and they are listed with the names that are in the code. That inversion is the product, not
> an omission — see [research-architecture](../research/research-architecture.md).
>
> Read out of the source on 2026-08-25.

## 0a. Code anchors

| What | File |
|---|---|
| The tree | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` |
| The route | `server/src/main/kotlin/io/konekt/screens/HomeRouting.kt` |
| Each counter card | `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/UsageCounterCards.kt` |
| How an allowance is written for a person | `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/UsageUnits.kt` |
| The wire type | `shared/components/src/commonMain/kotlin/io/konekt/components/UsageCounterCardComponent.kt` |
| The renderer | `client/src/commonMain/kotlin/io/konekt/client/render/UsageCounterCardRenderer.kt` |
| The live channel | `client/src/commonMain/kotlin/io/konekt/client/realtime/SseRealtimeSource.kt` |
| Tests | `server/src/test/kotlin/io/konekt/screens/HomeScreenTest.kt`, `feature/usage-server-data/src/test/kotlin/io/konekt/feature/usage/server/data/UsageCounterCardsTest.kt` |

## 0. Entry point and visibility

- **Entry point:** a `GET` on `HomeScreenResource`. There is no tab bar, no navigation graph and no
  deep link in this build.
- **Shown when:** the caller holds a valid access token. Without one the route answers `401` and the
  client's bearer plugin refreshes; a refresh the server refuses clears the session.

## 1. Screen states

The root is always a `column` with id `home`, spacing 16. What is inside it:

- [x] **Balance present:** two `text` nodes — `balance-label` ("Balance") and `balance-amount`,
  already formatted (`$50`, `$1,190.50`).
- [x] **Balance unreadable:** **both nodes are omitted.** Not a zero. Zero is a fact about an account
  and "we could not tell" is not, and a subscriber who reads the first when the second is true tops
  up money they already have.
- [x] **No counters:** one `banner` with id `home-no-plans` — "No plan is active on this line yet." —
  tone `info`, action text "See plans", `NavigateAction("app://plans")`. *The destination
  `app://plans` is a URI nothing in this build resolves; there is no plans screen yet.*
- [x] **Counters:** one `usage_counter_card` per counter, ordered by the repository (by the enum, not
  by the database — two screens that disagree about which counter comes first read as two products).
  Each card carries `state` = `normal` | `low` | `exhausted`, and **the copy changes with the state,
  not only the colour**.
- [ ] **Loading:** not built. A `skeleton` wire type exists in the dictionary and nothing on the
  server emits one.
- [ ] **Error:** not built as a screen. A failure answers `ApiError("internal_error", …)` with the
  detail in the log; what a client draws for it is not decided in this repository yet.

## 2. API integration

| Call | Contract | Endpoint document |
| :--- | :--- | :--- |
| `GET /api/v1/screens/home` | `HomeScreenResource` | [endpoint-home](../api/endpoint-home.md) |
| `GET /api/v1/realtime` (SSE) | `RealtimeStream.PATH` | [endpoint-home](../api/endpoint-home.md) |

## 3. Initialisation

**Input parameters:** none. Which subscriber this is comes from the verified token, never from the
request.

| Call | Condition | Result |
| :--- | :--- | :--- |
| `GET /api/v1/screens/home` | on open | the whole tree |
| `GET /api/v1/realtime` | while the screen is open | replaces single nodes by id |

| Call | Case | Handling | Screen state |
| :--- | :--- | :--- | :--- |
| `GET .../home` | `200` | render the tree | **Content** (possibly the no-counters banner) |
| `GET .../home` | `401` | the bearer plugin refreshes and retries once; a refused refresh clears the session | signed out |
| `GET .../home` | `5xx` | *not decided in this repository* | — |

## 4. UI elements, top to bottom

### 4.1. Balance

- **Field in the tree:** `balance-amount.text`
- **Display:** `MoneyFormat.format(balance)` — a whole amount drops its zero fraction (`$50`, not
  `$50.00`); a balance carries no sign, while a history row does.
- **On tap:** nothing. There is no top-up: a subscriber is created with a zero balance and nothing in
  the product adds money (`B-40`). The e2e stand seeds it with SQL, on purpose — a development-only
  top-up endpoint would be a production surface invented for a test.

### 4.2. Counter card

- **Fields:** `title`, `valueText`, `captionText`, `progress`, `state`.
- **Display:** everything except `progress` arrives ready. `valueText` is `"15.4 GB left"`,
  `"240 min left"`, `"200 SMS left"` — data crosses into gigabytes above 1024 MB, a whole number drops
  its zero fraction, and grouping is commas every three digits, matching the money format.
- **States, and what the caption says in each:**
  - `normal` — **no caption at all.** Saying something anyway is how a caption stops being read by the
    time it matters.
  - `low` (a tenth or less is left) — the projection and the offer:
    *"Minutes run out in about two days at your current pace. A 100-minute add-on costs $4."*
    When the projection cannot be computed — nothing spent yet, or an allowance granted moments ago —
    it falls back to *"Running low — under a tenth of your minutes is left."* rather than inventing a
    date.
  - `exhausted` — *"You have used all of your data."* plus the same offer.
- **`progress`:** `0..1`, and **null when there is no ceiling**. A bar cannot be drawn for an
  unlimited allowance, and drawing a full one would say the opposite of what is true.
- **On tap:** nothing. `UsageCounterCardComponent.action` exists on the wire and the server sets none:
  the add-on the caption offers cannot be bought, because nothing sells add-ons yet.

### 4.3. The "no plan" banner

- **On tap:** `NavigateAction("app://plans")` — see the caveat in §1.

## 5. Navigation (summary)

- "See plans" ──▶ `app://plans` — **a destination that does not exist in this build**.

Nothing else on this screen navigates.

## 6. Live updates

The screen is the reason the live channel exists. A frame carries an `UpdateComponentMessage` whose
`componentId` is `counter-<kind>` — the id the tree already has, derived from the counter and never
generated. The client replaces that node; it does not reload the screen.

Today the only thing that pushes one is the traffic simulator's consumer, and it is off unless
`SIMULATE_TRAFFIC=true`. A default deployment therefore has a stream that is correct and silent,
which looks exactly like a broken one.

After a dropped connection the client does **not** replay: `Last-Event-ID` is deliberately unused,
because an update is losable by design. `SseRealtimeSource` emits `streamRestarted` and the screen is
expected to refetch. *Which component does that refetch is not implemented in this repository.*

## 7. Quirks

- **"Low" is the server's judgement, not the client's.** It depends on the subscriber's rate of use,
  which lives on the server; a client deciding "low" from the number alone would have to guess.
- **The projection is a mean, not a trend.** Everything used divided by the whole life of the
  allowance — so somebody who watched a film on day one and nothing since is told two days when they
  have a fortnight. A trend needs a table of decrements this product does not keep. The word "about"
  in the copy is doing real work, and `UsageUnits.approximately` is deliberately vague ("about two
  days", never "1.8 days").
- **An unknown `state` word draws the ordinary card**, never nothing. That is the whole additive
  bargain of the vocabulary.
- **The card's shape comes from the design system, not from the renderer.** A brand's radii are a
  client build constant; a renderer that rounded its own corners would be a second shape scale nobody
  could find.
- **The "no plan" banner drew a red error for most of this build's life, and nothing knew.** `banner`
  was in the dictionary with no renderer, so the registry's own fallback took it — "Unknown component",
  in red, on the first screen every subscriber sees. It is not covered by the degradation story either:
  that block, its sink and the whole forward-compatibility argument are about types the client cannot
  DECODE, and a type that decodes and cannot be DRAWN never reaches them.

  **Every test missed it for one reason: they all top up and buy first**, so this screen always had a
  counter and the banner was never sent. It took running the iOS application against the stand with a
  fresh account. `ClientAgainstStandTest` now signs in and asserts nothing else — the state a real
  first-time subscriber gets is the one that had never been exercised.
