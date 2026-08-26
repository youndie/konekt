---
id: design-brand-kit
title: konekt — the brand kit, and which half of it needs a client release
type: design
status: active
date: 2026-08-25
---

# The brand kit

An operator takes konekt and puts their own name on it. This document says exactly what that costs,
because the honest answer has two halves with different price tags and the difference is not
negotiable — it is a property of the wire konekt speaks.

**The colour kit ships from the server. The shape scale ships with the client.**

| What you want to change | Where it lives | What it costs |
|---|---|---|
| every colour, light and dark | `server/src/main/resources/themes/<brand>.json` | a server deploy |
| corner radii; pills versus rounded rectangles | `client/src/commonMain/kotlin/io/konekt/client/theme/KonektShapeScale.kt` | an application release |
| the type scale, the two font faces | the client | an application release |

## Why the split is not a design choice

`kompot-core` declares exactly two kinds of token — `ColorToken` and `TypographyToken` — and the
modifier vocabulary carries no radius, no border and no elevation. Shape reaches a renderer through
`KompotDesignSystem.resolveSurface(role)`, and a `SurfaceRole` is documented in the toolkit as a
client-side key that never travels. So there is no way to spell a radius on the wire, and there is no
version of konekt in which an operator changes a radius by editing a file on the server.

That is deliberate rather than an omission: a server that could name a radius is a server that can
round a control away until it is unreachable. The verified reading is in
[research-architecture](../research/research-architecture.md) §1.2, and the decision is D2.

Typography sits on the colour side of the wire and on the client side in practice. `KompotTextStyle`
carries size, line height, weight, letter spacing and colour — **and no font family** — so a face
named by the server would not arrive anyway. The two brands in this build share one type scale, which
is why neither kit carries a `typography` block at all.

## Writing a colour kit

A kit is one JSON file, named after the brand, whose `id` is the same brand:

```
server/src/main/resources/themes/brand-a.json    id = "brand-a"
server/src/main/resources/themes/brand-b.json    id = "brand-b"
```

The file is the wire document. `BrandThemeCatalogue`
(`server/src/main/kotlin/io/konekt/theme/BrandThemeCatalogue.kt`) reads it at startup and serves the bytes
unchanged — the server never decodes a theme, so a field a future kompot adds passes through to the
client instead of being quietly dropped at a version boundary.

**Name every token, in both palettes.** There are twenty (`M3Colors.all` in
`kompot-ds-material`), and a token a kit does not name is not an error and is not logged: kompot's
overlay answers from the theme where the theme has a token and from the client's built-in palette
where it does not. The result is one control, in one state, wearing Material's default purple inside
your brand — and the person who finds it is a customer with a screenshot. The same is true of a
mistyped value: a hex the toolkit cannot read makes the token fall through exactly as an absent one
does. `BrandKitsTest` refuses both.

Values are `#AARRGGBB`. Six digits and three digits also parse, but eight is what the two kits here
use, because a brand kit is one of the places where "was that opaque?" is worth not having to ask.

**Both palettes.** `dark: null` is legal in the toolkit and means "keep the client's built-in dark
palette", which for a rebranded application means an ink brand that turns teal at sunset. Neither kit
here uses it and the guard refuses it.

## Adding a brand

1. Write `server/src/main/resources/themes/<brand>.json`, all twenty tokens, light and dark.
2. Add the brand's shape scale to `KonektShapeScale.byBrand` in the client.
3. Point the server at it (see *Serving it* below).

Step 2 is the one that is easy to skip, and skipping it is silent: a brand with no scale is served
happily and drawn with brand A's radii, which looks like a rendering bug rather than a missing
release. `BrandKitsTest` fails the build in both directions — a served kit with no scale, and a scale
for a brand nobody serves.

## The two brands in this build

| | brand A | brand B |
|---|---|---|
| palette | Signal — teal primary `#0B6B60`, warm secondary | Ink — near-black `#17171B`, `#D24A2C` accent |
| `lg` | 36 | 22 |
| `md` | 20 | 12 |
| `sm` | 12 | 8 |
| buttons | pill | rounded rectangle |

Both come from [design-app-canvas](design-app-canvas.md): brand A from the token swatches, brand B
from section 08. The canvas names the primaries, the accents, the surfaces, the backgrounds and the
outlines directly; the remainder — the `on_*_container` pairs, mostly — is derived from those rather
than drawn, and those are the values to revisit first if a brand looks close but not right.

### A measured caveat about the buttons

**Brand B's headline shape change is invisible on a button at Material's default height, and this is
not a bug in konekt.** A Compose `RoundedCornerShape` clamps a corner to half the smaller dimension,
so on a 40dp-high button every radius of 20dp or more draws the identical pill — and brand B asks for
22.

Measured on the guard's own fixture, in pixels differing from the pill:

| radius | 8 | 12 | 16 | 18 | 19 | 20 | 21 | 22 | 24 | 30 |
|---|---|---|---|---|---|---|---|---|---|---|
| pixels | 392 | 334 | 252 | 198 | 165 | **0** | **0** | **0** | **0** | **0** |

and by button height, brand A against brand B:

| height | 44 | 46 | 48 | 52 | 56 | 72 |
|---|---|---|---|---|---|---|
| pixels | **0** | 182 | 238 | 328 | 432 | 902 |

The canvas's minimum touch target is 48, which is the first ordinary size at which the two brands are
distinguishable. So a reviewer comparing a running brand B against a running brand A should expect the
buttons to differ only where they are taller than 44dp, and should look at the fields and the cards
for the rest. `BrandSwitchTest` draws its buttons at 48 for exactly this reason; an earlier version
drew them at the default, found the two brands pixel-identical, and reported it as "the shape moved
nothing".

### Brand A's `lg` is not drawn anywhere at all

One level below the clamp, and found by `B-28` rather than by review: **changing brand A's `lg` from
36 to any other value changes nothing on any screen.** Measured — `lg` was set to 8 and all eight
goldens still matched, byte for byte.

The reason is in `KonektDesignSystem.resolveSurface`. `largeShape` is read by exactly one thing,
`KonektShapeScale.buttonShape`, and only on the `!pillButtons` branch:

| role | shape it takes |
|---|---|
| `Button`, `button("primary")`, `button("quiet")` | `buttonShape` — a **pill** for brand A, so `lg` never appears |
| `Container` | `mediumShape` — `md` |
| `Field`, `ReadOnlyField` | `smallShape` — `sm` |

So brand A's 36 is a number the canvas states and the build does not use, while brand B's 22 IS drawn
because brand B turns pills off. The control for that: setting brand B's `lg` to 8 fails brand B's two
goldens and nothing else.

This matters when reading a golden failure. **A change to brand A's scale is visible through `md` and
`sm`** — setting brand A's `md` from 20 to 8 fails all six frames drawn in brand A (the four counter
states and brand A's light and dark pair) and neither of brand B's. That is the isolation property
worth having; `lg` cannot demonstrate it.

**Answered, and the answer is the second.** Brand A's scale says plainly that `lg` is inert while pills
are on: `KonektShapeScale.largeIsDrawn` is `!pillButtons`, and `InertRadiusIsDeclaredTest` holds it in
both directions — brand A must not claim to draw it, brand B must.

The rejected answer is giving 36 a surface of its own. A pill is a shape that follows the height of
what it wraps, so it HAS no radius to take from a scale; inventing a full-bleed card or a sheet so the
number has somewhere to land would be designing a product surface to satisfy a screenshot. Deleting
`large` is the opposite mistake — brand B draws it, precisely because brand B turns pills off.

The guard is what keeps the silence a decision rather than an oversight: without it the next reader has
two plausible ways to "fix" it and both are wrong.

### Light and dark must be asked for together

`B-28` photographed brand A in light and found a dark card under a light button. Not a palette
mistake — the two halves of `KonektTheme` were asking different questions:

* `toMaterialColorScheme(base, darkMode)` builds the Material scheme from the `darkMode` the caller
  passed;
* the toolkit's `rememberKompotDesignSystem(theme, fallback)` builds a `RemoteThemeDesignSystem` with
  `darkModeOverride = null`, and that class then resolves every `ColorToken` through
  `isSystemInDarkTheme()` — the **host machine's** appearance setting.

On a machine set to dark, `KonektTheme(kit, darkMode = false)` therefore drew brand A's card in
`#18211F` (the DARK `surface_variant`) underneath a button in `#0B6B60` (the LIGHT `primary`), and the
title in the dark palette's `on_background` on a near-white ground, which is close to invisible. On a
machine set to light, the *dark* screen was the wrong one instead: reverting the fix and verifying on
the Linux box failed exactly `Brand - A Dark` and `Brand - B Dark`, the mirror image of the same
defect.

`KonektTheme` now constructs `RemoteThemeDesignSystem` itself and passes `darkModeOverride = darkMode`.
The class is public and takes the parameter; only the `remember`-shaped convenience does not, which is
worth an upstream note rather than a second workaround. Anything else in this repository that builds a
design system by hand has to pass it too — `BrandSwitchTest` was changed for the same reason.

## Serving it

`themeRoutes(path, catalogue)` in `server/src/main/kotlin/io/konekt/theme/ThemeRoutes.kt` answers the
kit as `application/json`, outside `authenticate` — the sign-in screen is the first thing every
subscriber sees and it has to be branded before a token exists. A kit carries no subscriber data.

**As of B-22 the composition root does not call it yet**, and the endpoint's path constant does not
exist: this repository writes no endpoint path outside a `*-shared-api` module, and the module for
this one is still to be created. Until that lands, the two kits are real files with real guards over
them and the client half is complete and tested, but nothing is served over HTTP. The item's handoff
says precisely what is missing.

## What is checked, and by what

| Property | Guard |
|---|---|
| a kit is valid, self-named, complete in both palettes, and every value parses | `client/src/jvmTest/kotlin/io/konekt/client/theme/BrandKitsTest.kt` |
| every served brand has a shape scale compiled in, and no scale is orphaned | the same file |
| a colour kit repaints the screen and moves nothing on it | `client/src/jvmTest/kotlin/io/konekt/client/theme/BrandSwitchTest.kt` |
| the shape scale moves the screen with no wire change at all | the same file |
| the composition root resolves the scale from the served brand's name | the same file |
| a server theme does not discard konekt's surface customisations | `client/src/jvmTest/kotlin/io/konekt/client/theme/SurfaceSurvivesTheThemeTest.kt` |
| each brand still draws the frame it drew last time, light and dark | the goldens in `client/src/jvmTest/snapshots/`, compared by `./gradlew :client:viddikVerify` |
| a light frame carries no colour that exists only in the dark palette | `client/src/jvmTest/kotlin/io/konekt/screenshots/GoldenContentTest.kt` |
| the recorded pair differs in geometry and not only in colour | the same file |

The kits those guards read are the files the server ships, not fixtures — so editing brand B's palette
into brand A's fails the build rather than turning the demonstration into a tautology.

The goldens are what the in-run guards above cannot be: those compare two frames within one run, so a
change to konekt's own surfaces moves both frames together and passes. A committed photograph does not
move with the code. `B-28` brought them; recording is
`LOCAL=1 ./gradlew :client:viddikRecord` on the Mac — the one-way replica reverts anything a task
writes on the Linux box — and the goldens verify unchanged on Linux, which was measured rather than
assumed.
