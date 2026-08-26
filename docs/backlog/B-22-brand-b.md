---
id: B-22
title: "Brand B: the colour kit ships from the server, the shape scale ships with the client"
status: wip
priority: P1
size: M
stage: stage-m3-product
blocked_by: [B-04, B-43]
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

- AC PARTLY, and the missing part is HTTP rather than brand B. The two kits exist as the files the
  server ships, and the client repaints from **those files** with nothing rebuilt: `BrandSwitchTest`
  renders the toolkit's own renderers under brand A's kit and brand B's kit and asserts the frame
  repaints (`repainted > 0`) and the silhouette does not move (`moved == 0`). What is NOT observable
  is the word *served*: nothing calls `themeRoutes` yet, because the endpoint's path constant belongs
  in a `*-shared-api` module this lane could not create and `:server` has no `kompot-theme` on its
  compile classpath. The handoff names both lines.
- AC OK. Brand B's radii are `KonektShapeScale.BrandB` — 22 / 12 / 8, rounded rectangles — resolved
  from the served brand's `id` by `KonektTheme`, and
  [design-brand-kit](../design/design-brand-kit.md) is the operator material: a table of what costs a
  deploy and what costs a release, on its first screen.

**The demonstration is a CROSS PRODUCT rather than a before/after pair**, and that is the part worth
copying. Two frames that differ in both colour and shape satisfy "brand B looks different" while the
radii could be arriving from anywhere. Rendering both kits against both scales splits the claim in
two: change the kit and hold the scale (repaints, does not move); change the scale and hold the kit
(moves, no wire change). Each half is a positive control for the other.

**Three things this cost that were not in the item.**

**`:server` cannot see `kompot-theme`, so the catalogue does not decode a theme — and that turned out
to be the better design.** `server/build.gradle.kts` is outside this lane, so `KompotTheme` was not
available; the catalogue therefore validates what a pass-through can validate without knowing the
schema (the kit exists, it is a JSON object, its `id` matches the brand it is served as) and answers
the bytes verbatim. That is now the argued position rather than the fallback: a server that decodes
and re-encodes a theme silently drops any field the toolkit adds after this server was built.

**Completeness of a kit is a CLIENT-side property, which is why the guard lives in a client test.**
Whether a kit covers every token is a statement about `M3Colors.all` — a vocabulary the server has no
opinion about. `BrandKitsTest` reads the real files out of `server/src/main/resources/themes` for the
same reason a copied palette would be a test that agrees with itself.

**Brand B's headline shape change is invisible on an ordinary button, measured.**
`RoundedCornerShape` clamps a corner to half the smaller dimension, so at Material's default 40dp
button height every radius of 20dp or more draws the identical pill — and brand B asks for 22. The
sweep against the pill was 392 / 334 / 252 / 198 / 165 pixels at 8 / 12 / 16 / 18 / 19 dp and exactly
0 at 20 / 21 / 22 / 24 / 30. By height, brand A against brand B: 0 at 44dp, 182 at 46, 238 at 48
(the canvas's minimum touch target), 902 at 72. The first version of the guard drew default-height
buttons, found the brands pixel-identical and read it as "the shape moved nothing". The numbers and
what they mean for a reviewer are in [design-brand-kit](../design/design-brand-kit.md), and they are
a hazard for `B-28`'s second AC.

**All four guards were proved by mutation**, each failing by name and then restored: dropping
`on_primary_container` from brand B's light palette (completeness), adding a `brand-c.json` with no
scale (coverage, both directions), replacing `forBrand(theme?.id)` with a constant in `KonektTheme`
(the composition root reads the brand name), and collapsing brand B's radii onto brand A's (the
cross product).

- Anchors: `client/src/commonMain/kotlin/io/konekt/client/theme/KonektShapeScale.kt`,
  `client/src/commonMain/kotlin/io/konekt/client/theme/KonektTheme.kt`,
  `client/src/jvmTest/kotlin/io/konekt/client/theme/`, `server/src/main/resources/themes/`,
  `server/src/main/kotlin/io/konekt/theme/`.

Two deviations from the anchor as written. The package in this module is `io.konekt.client.theme`,
not `io.konekt.theme` — that is where `KonektDesignSystem` already lived. And the file is
`KonektShapeScale.kt` rather than `ShapeScales.kt`, because ktlint's `filename` rule refuses a file
holding a single class under any other name.

Background: [research-architecture](../research/research-architecture.md) §1.2, D2;
[design-brand-kit](../design/design-brand-kit.md).
