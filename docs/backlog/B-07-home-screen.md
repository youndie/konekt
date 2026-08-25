---
id: B-07
title: "Home: balance and counters, drawn from the server"
status: done
priority: P0
size: M
stage: stage-m0-wire
epic: feature-usage-counters
blocked_by: [B-03, B-04, B-06]
---

# B-07 — Home: balance and counters, drawn from the server

The first screen, and the one that proves the loop: a server route builds a tree, the client renders
it, and nothing about the layout lives in the client. Canvas section 01, three frames — home, home
dark, plan usage detail — with the counter states in one screen.

- **The decision and its reason.** The response goes out through `respondKompotComponent`, never
  `call.respond`. Research §1.11: a plain respond drops the `"type"` discriminator on the root of the
  tree — nested children are unaffected, which is what makes it easy to miss — and the client then
  receives an unknown component for the whole screen and, by §1.4, draws nothing.
- The rejected alternative is a generic serialisation helper of our own. It would be the same call
  with a different name and one more place to get it wrong.
- **The screen is assembled in `:server`**, not in a feature. Its two halves belong to two features —
  the balance to the purchase ledger, the counters to usage — and a feature reaching into the other's
  repository to draw one screen is how two features become one. The composition root composes.
- Not covered: live updates. The counters are fetched here and become live in `B-15`/`B-16`.

- AC OK: the low state changes the copy, not only the colour — "Minutes run out in about two days at
  your current pace. A 100-minute add-on costs $4." Both halves are asserted, because both are things
  a later edit could quietly drop, and the caption falls back to the plain fact when there is no rate
  to project from rather than inventing a date.
- AC OK: no `call.respond` with a `KompotComponent` argument exists anywhere in the server, enforced
  by `CallRespondUsageTest` reading the source — there is no signature to forbid it. Proved to bite:
  rewriting the home route to `call.respond` fails it by name.

**What this item found, and it was not in the plan.**

**The usage feature was in the graph of nothing.** `Application.kt` carried five imports of it and not
one use, so the counters were unreachable: no route could read one, `LoadCountersUseCase` was never
constructed, and **a completed purchase granted no allowance at all**. Every test passed, because each
one assembled what it needed by hand — which is what a test does. `B-16` closed on ACs that were all
about the chain being *tested*.

Three things came out of that. The feature is bound (`usageModule`). `ProvisionInterceptor` now grants
the plan's allowance in the same step as the capture, and **revokes it on compensation** — money that
comes back while the gigabytes stay is a rollback that costs the operator rather than nobody. And
`FeatureModulesReachTheGraphTest` reads the composition root as text and fails on a feature module
nothing installs; `KoinGraphTest` cannot see this class of defect, because it verifies the modules it
is *given*.

**A plan now says what it is made of.** `Plan.dataMb`, carried onto the saga payload at start time
rather than re-read at settlement: the payload is what was agreed, and a catalogue that moved in
between would grant an allowance nobody was shown.

**The projection is a mean, and the arithmetic constrains the fixture.** The rate is everything spent
over the whole life of the allowance, so a counter with a tenth left has a ninth of its elapsed life
ahead of it — "about two days" needs eighteen days elapsed, not two, and no shorter window can produce
it. The word "about" in the copy is doing real work, and `UsageCounterTest` covers both ways the
projection refuses to answer rather than returning a misleading zero.

- Anchors: `server/src/main/kotlin/io/konekt/screens/`,
  `client/src/commonMain/kotlin/io/konekt/client/render/`,
  `feature/usage-server-data/src/main/kotlin/io/konekt/feature/usage/server/data/UsageCounterCards.kt`,
  `server/src/test/kotlin/io/konekt/http/CallRespondUsageTest.kt`,
  `server/src/test/kotlin/io/konekt/di/FeatureModulesReachTheGraphTest.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.4, §1.11;
[design-app-canvas](../design/design-app-canvas.md) section 01.
