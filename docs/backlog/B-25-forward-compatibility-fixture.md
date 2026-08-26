---
id: B-25
title: "A route that sends a component the client does not know, on purpose"
status: done
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
- AC PARTIAL, and the reason changed. Drawing it is done: `:client:standTest` renders this screen
  through the real holder against the running stand and asserts both blocks and both neighbours. What
  cannot be met is "both densities", and not for want of a fixture — see below.

## Not covered, and now demonstrable

The degradation record still reaches nothing: `KompotDegradationSink` counts an unknown component and
konekt binds no sink, so this screen makes the blindness kompot#81 was filed about visible for the
first time rather than fixing it. That is `B-26`'s third acceptance criterion, and this route is what
will finally exercise it.

## The CARD density was unreachable, and the container is what now decides

`UnknownBlockRenderer` chooses between a LINE and a CARD by reading `LocalUnknownBlockDensity`, whose
default is LINE. Its own comment said the density is "chosen by where the block sits".

**Nothing chose.** `grep` found the composition local declared, read once by the renderer, and provided
by exactly one caller: `UnknownBlockRendererTest`, which set it by hand. No production code path could
reach the CARD branch, so a screen could not demonstrate both densities however its components were
arranged — this one included. That is the `written-but-never-called` shape applied to a DECISION rather
than to a function: the branch existed, it was tested by a fixture that supplied its own condition, and
the condition was supplied nowhere else.

**The container decides now**, and the rejected alternative was the screen holder. A holder knows the
SCREEN and not the neighbourhood, so a mixed screen would get one answer for all of it — while "where
the block sits" is a fact about neighbours, which is exactly what a container is. `ColumnDensityRenderer`
and `RowDensityRenderer` provide the local and delegate to the toolkit's own renderers; a copy of
`ColumnRenderer` would be a second layout to keep in step with kompot's for one line of context.

**And the screen did not carry its own intent.** Its comment claimed one block of each density while
both sat as siblings in one column, so both drew the same shape — the intent was written down and the
tree did not have it. One is inside a `row` now.

- AC MET: "hitting the route draws the placeholder in both densities with the rest of the screen
  intact." `:client:standTest` renders this screen through the real holder against the running stand
  and asserts exactly one LINE, exactly one CARD, and both known neighbours. Exact counts rather than
  "at least one", because one block plus one silently-dropped component is the failure this screen
  exists to make visible. Mutation-proved: with the container renderers out of the registry, the test
  fails.
- AC MET: the route is absent from the production route table, asserted over `konektRoutes` itself
  rather than over a flag — a development route reaches a deployment by being in the list every
  deployment mounts.

## The degradation record now reaches something

When this item was written the record reached nothing: `KompotDegradationSink` counted an unknown
component and konekt bound no sink, so this screen made the blindness kompot#81 was filed about visible
for the first time rather than fixing it. `B-26` closed that — the record reaches tracy with
`originalType` indexed and leaves a katcher breadcrumb, and this screen is what exercises it.
