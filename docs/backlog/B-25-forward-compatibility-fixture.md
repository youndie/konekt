---
id: B-25
title: "A route that sends a component the client does not know, on purpose"
status: wip
priority: P2
size: S
stage: stage-m4-proof
blocked_by: [B-05]
---

# B-25 — A route that sends a component the client does not know, on purpose

The canvas draws the unknown-component block and labels the example `type: esim_transfer_widget`. The
client in this repository registers every type the server sends, so it can never reach that state —
the frame is a picture of something the product cannot enter, which makes it undemonstrable and, more
to the point, untested.

- **The decision and its reason.** One development-only route emits a wire type deliberately absent
  from the client registry. It exercises the replacement renderer, the tracy record and the "everything
  around it still works" claim in one place, and it is the only way any of those are ever exercised.
- The rejected alternative is an old client build kept around for demonstrations. It rots, and it
  tests the version it was built at rather than the current renderer.
- Not covered: an unknown **action**, which the toolkit does not surface at all — see
  [U2](../research/research-upstream-proposals.md#u2).

- AC: hitting the route draws the placeholder in both densities with the rest of the screen intact.
- AC: the route is absent from the production build, asserted by a test over the route table.
- Anchors: `server/src/main/kotlin/io/konekt/screens/dev/ForwardCompatScreen.kt`.

Background: [research-architecture](../research/research-architecture.md) Risk 5.

## What landed

`/api/v1/dev/screens/forward-compat` sends a tree with two `esim_transfer_widget` components — the
canvas's own example name — among known ones, above and below.

**The type is declared in `:server` and registered by hand in the server's `Json`, and that placement
is the whole mechanism.** The dictionary module is generated from `@KompotComponentMarker` and BOTH
sides read it, so a type declared there is a type the client registers: it could never arrive unknown,
and a fixture built on one would demonstrate nothing.

**Two of them, and known neighbours around them**, because the claim is not "the block appears" but
"everything around it still works" — a screen containing only the unknown component looks identical
whether the rest of the tree survived or not. Two also because the replacement renderer has two
densities, LINE and CARD, chosen by where the block sits: a decision no unit test of the renderer can
make for itself.

The test decodes the server's own encoding with a Json assembled from exactly what a CLIENT has — the
toolkit's modules and the generated dictionary, nothing of the server's. Proved by giving that Json
the type: `expected: <2> but was: <0>`, which is the day somebody moves the declaration into
`:shared:components`.

- AC MET: the route is absent from the production route table, asserted over `konektRoutes` itself
  rather than over a flag — a development route reaches a deployment by being in the list every
  deployment mounts.
- AC PARTIAL: "the placeholder in both densities with the rest of the screen intact" is proved on the
  WIRE — both components arrive unknown, both keep their `originalType`, both neighbours survive — and
  not yet on a screen. Drawing it needs the client's composition root to point at this address, which
  is `B-43`'s remaining half.

## Not covered, and now demonstrable

The degradation record still reaches nothing: `KompotDegradationSink` counts an unknown component and
konekt binds no sink, so this screen makes the blindness kompot#81 was filed about visible for the
first time rather than fixing it. That is `B-26`'s third acceptance criterion, and this route is what
will finally exercise it.
