---
id: B-07
title: "Home: balance and counters, drawn from the server"
status: open
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
- Not covered: live updates. The counters are fetched here and become live in B-15.

- AC: the low state changes the copy, not only the colour — "minutes run out in about two days at
  your current pace" with the add-on price, as on the canvas.
- AC: no `call.respond` with a `KompotComponent` argument exists anywhere in the server.
- Anchors: `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt`,
  `client/src/commonMain/kotlin/io/konekt/render/UsageCounterCardRenderer.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.11,
[design-app-canvas](../design/design-app-canvas.md) section 01.
