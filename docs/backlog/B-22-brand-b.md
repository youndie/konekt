---
id: B-22
title: "Brand B: the colour kit ships from the server, the shape scale ships with the client"
status: open
priority: P1
size: M
stage: stage-m3-product
blocked_by: [B-04]
---

# B-22 — Brand B: the colour kit ships from the server, the shape scale ships with the client

Canvas section 08 is brand B on the same markup: ink palette, radii 36→22 and 20→12, pills become
rounded rectangles, typography unchanged. Research §1.2 established that the first half of that ships
from the server and the second half cannot: `kompot-core` has exactly two token kinds, `ColorToken`
and `TypographyToken`, the modifier vocabulary carries no radius, and a `SurfaceRole` is documented as
a client-side key that never travels.

- **The decision and its reason.** Both shape scales are compiled into the client, and the client
  selects one. The theme already carries a brand `id`, so the selection is a client-side resolution of
  a server-sent name — the same shape as `ColorToken("promo_gold")` — rather than appearance on the
  wire. Note that `KompotTheme.id` is documented as diagnostics-only, so this is konekt reading it for
  a purpose the toolkit does not promise; the comment in the code says so.
- The rejected alternative is a shape token of our own on the wire. It requires a fork of
  `kompot-core` and breaks the exact property this project should be demonstrating.
- Not covered: a third brand. Two scales prove the mechanism; the third is a client release either way.

- AC: switching the served theme repaints the application in brand B's colours with no client rebuild.
- AC: brand B's radii are present, and the operator material says plainly that they came with the
  client and not with the theme.
- Anchors: `client/src/commonMain/kotlin/io/konekt/theme/ShapeScales.kt`, `server/src/main/resources/themes/`.

Background: [research-architecture](../research/research-architecture.md) §1.2, D2.
