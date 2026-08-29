---
id: B-86
title: "Changing tariff has a saga, a table, a confirmation and no screen: only an e2e test can reach it"
status: open
priority: P0
size: M
stage: stage-m7-completeness
---

# B-86 — A whole vertical whose only user is a test

[B-21](B-21-tariff-change.md) closed with a working feature. What it does not have is any way in:

- `Shell.graph()` lists eight destinations and none of them is a tariff screen;
- no component anywhere sends `ChangeTariffRequest` — the only senders are
  `e2e/src/test/kotlin/io/konekt/e2e/TariffChangeScenarioTest.kt` and the server's own tests;
- `:client` does not depend on `feature:tariff-shared-api` at all. Seven `*-shared-api` modules are
  on its dependency list and that one is not, so the client could not decode the request even if a
  screen posted it;
- `StaticTariffCatalogue` holds three tariffs nothing displays.

This is the shape [B-56](B-56-unreachable-screen-guard.md) was written for — *nothing fails when a
screen the server serves is the destination of no action anywhere* — and it slipped past because
there is no screen at all: the guard watches served screens, and an unserved feature is invisible to
it.

For a reference build this is the expensive kind of gap. A tariff change is the second saga in the
product and the one that is **not** about money arriving; it is about a subscription changing under
a subscriber who has to agree to it. That is the case petich's suspend/resume exists for, and today
it is demonstrated only in a test harness where the confirmation is a function call.

- **The decision: give it the screens it already has the server for** — the current tariff on the
  profile screen, a catalogue with the current one marked, and the confirmation stating what changes
  and when it takes effect. The saga, the routes and the repository are done; this is the wire and
  the tree.
- **The confirmation is the reason to do this rather than to drop it.** A purchase's confirmation
  answers "spend this?", and a tariff change's answers "change what you are on?" — a different
  refusal, a different reversal, and the second is what proves the engine is not being used for one
  shape of transaction only.
- **The rejected alternative is to delete the vertical.** Three tariffs, a saga and a migration are
  already carried by every build and every deploy; deleting them costs the same work as finishing
  them and removes a demonstration.
- This item does **not** add downgrade rules — [B-21](B-21-tariff-change.md) recorded *any tariff
  can be chosen from any tariff* as out of scope and that stands — and does not move the catalogue
  out of Kotlin, which is a non-goal ([B-80](B-80-the-non-goals-are-nowhere.md)).

- AC: a subscriber reaches the tariff catalogue from the profile tab, chooses one, sees what it costs
  and what changes, confirms, and lands on a result screen that names the outcome.
- AC: the refusal path is a screen with a reason, not a status code, the way
  [B-68](B-68-a-refused-purchase-never-says-why.md) settled it for purchases.
- AC: `:client` depends on `feature:tariff-shared-api`, and the action module is registered in the
  application's `Json` — [B-73](B-73-the-stand-registered-no-actions.md) is what happens otherwise.
- AC: the destinations are in `Shell.graph()`, so `EveryScreenIsReachableTest` covers them.
- Anchors: `server/src/main/kotlin/io/konekt/screens/Shell.kt`,
  `server/src/main/kotlin/io/konekt/tariff/TariffData.kt`,
  `feature/tariff-shared-api/src/commonMain/kotlin/io/konekt/feature/tariff/shared/api/TariffApi.kt`,
  `client/build.gradle.kts`, `e2e/src/test/kotlin/io/konekt/e2e/TariffChangeScenarioTest.kt`.
