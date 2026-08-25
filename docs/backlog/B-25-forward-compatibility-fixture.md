---
id: B-25
title: "A route that sends a component the client does not know, on purpose"
status: open
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
