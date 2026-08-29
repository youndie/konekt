---
id: B-88
title: "Roaming has no screen of its own, and the only way to start a package is a public dev route that names the subscriber in a query"
status: open
priority: P1
size: M
stage: stage-m7-completeness
---

# B-88 — The state the feature exists for is reachable only by a route that must never ship

[B-19](B-19-roaming.md) built the good half: a package bought at home lies dormant, activates on
first use abroad, and is dated from that moment rather than from the purchase. That is a real
telecom rule and it is implemented.

What is missing is everything around it:

- **No roaming screen.** Packages appear as cards on home; there is no zone list, no country search,
  no place that answers "what do I have for this trip".
- **`feature/roaming` has no `-shared-api` module**, so nothing about roaming is on the wire as a
  contract the client knows — the vertical is server-only.
- **The client does not know `dormant`.** `UsageCounterCardRenderer` draws it as an ordinary card,
  which is correct degradation and the wrong colour for the one state this feature was built to
  show.
- **Activation is a dev route.** `POST /api/v1/dev/roaming/arrive` is public, gated by `DEV_SCREENS`,
  and takes `subscriberId` from the **query rather than the token** — so where it is enabled, anyone
  can start a stranger's package and begin spending it.

For a reference build the last point is the sharpest: the demonstration of a dormant package
becoming active — the whole feature — runs through the one route that is documented as never
shippable.

- **The decision: give roaming a screen, put `dormant` in the client's vocabulary, and move arrival
  onto the simulator.** The traffic chain already publishes zoned events for trips under way
  (`TrafficSimulator`'s `travelling`); what it cannot do is *begin* one. A simulated arrival is the
  same kind of fiction as simulated traffic and belongs beside it, behind `SIMULATE_TRAFFIC`, taking
  the subscriber from the same place the rest of the chain does.
- **The rejected alternative is to authenticate the dev route.** That makes it safe and leaves the
  demonstration depending on a route the deployment must not enable, which is the actual problem.
- **The rejected alternative is a screen with no arrival.** A roaming screen whose packages never
  start is a list of rows in one state.
- This item does **not** observe a real network attachment — [B-19](B-19-roaming.md) recorded that
  as out of scope and it stays out; nothing here watches a device land anywhere.

- AC: a roaming destination is in `Shell.graph()` and shows the subscriber's packages by zone, with
  dormant, active and expired distinguished on screen.
- AC: a dormant package looks dormant in the client — the state has a renderer branch and a
  screenshot case, not a fallback.
- AC: `/api/v1/dev/roaming/arrive` is deleted, and the scenario that used it drives arrival through
  the simulated chain instead.
- AC: `RoamingScenarioTest` passes against the new path.
- Anchors: `server/src/main/kotlin/io/konekt/roaming/`,
  `server/src/main/kotlin/io/konekt/roaming/dev/ArriveRouting.kt`,
  `feature/roaming-server-domain/src/main/kotlin/io/konekt/feature/roaming/server/domain/RoamingDomain.kt`,
  `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficChain.kt`,
  `client/src/commonMain/kotlin/io/konekt/client/render/UsageCounterCardRenderer.kt`.
