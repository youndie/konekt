package io.konekt.client.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.ds.material.toMaterialColorScheme
import io.github.youndie.kompot.theme.KompotTheme
import io.github.youndie.kompot.theme.client.RemoteThemeDesignSystem

// Where a brand becomes a screen — and where the two halves of a brand kit, which have different
// lifetimes, are put back together.
//
// **The colour kit comes off the wire and the shape scale comes out of this build.** That split is
// forced rather than chosen: `kompot-core` has exactly two token kinds and neither of them is a
// shape, so an operator's radii cannot travel (research-architecture §1.2, D2). What travels is the
// brand's NAME, and this function resolves it — the same shape as a `ColorToken("promo_gold")`,
// where the server names something and the client decides what it looks like.
//
// BOTH HALVES OF THE COLOUR APPLY, and leaving out either makes the repaint look half-done in a way
// that is hard to attribute:
//
//   * `toMaterialColorScheme` repaints Material's own scheme, which is what the renderers' built-in
//     colours read — a button's container, a card's surface;
//   * `RemoteThemeDesignSystem` overlays the token lookups, which is what a server-named
//     `ColorToken` resolves through.
//
// **BOTH HALVES MUST ALSO AGREE ABOUT DARK MODE, AND THE OBVIOUS SPELLING DOES NOT MAKE THEM.** The
// toolkit's convenience wrapper `rememberKompotDesignSystem(theme, fallback)` constructs a
// `RemoteThemeDesignSystem` with `darkModeOverride = null`, and that class then resolves every token
// through `isSystemInDarkTheme()` — the HOST's appearance setting — while the Material scheme above
// is built from the `darkMode` this function was given. On a machine set to dark, `KonektTheme(theme,
// darkMode = false)` therefore drew a light screen with dark surfaces: measured in B-28 on brand A's
// own kit, where the counter card came out `#18211F` (brand A's DARK `surface_variant`) underneath a
// button painted `#0B6B60` (brand A's LIGHT `primary`). The frame was half of each, and which half
// depended on the machine.
//
// So the override is passed explicitly. `RemoteThemeDesignSystem` is public and takes it; only the
// `remember`-shaped convenience does not, which is worth reporting upstream rather than working
// around twice.
//
// `theme = null` is the state before the kit has arrived, and it is a real state rather than a test
// convenience: the application draws with its built-in palette and brand A's shapes until `/theme`
// answers. The fallback is then returned UNCHANGED, which is why every guard in this package asserts
// it got something else back before believing itself.
@Composable
fun KonektTheme(
    theme: KompotTheme?,
    darkMode: Boolean,
    // THE TYPE SCALE, AND A CALLER MAY HAND IN ANOTHER. The default is the product's — the canvas's
    // faces, sizes and weights. The screenshot harness passes the same scale with viddik's pinned
    // family instead, because a golden must render to the pixel on every machine and a shipped
    // typeface, static and unhinted, still does not (`B-114` G1, measured at 0.07–0.08%).
    typography: Typography = KonektTypography.material,
    content: @Composable () -> Unit,
) {
    val base = if (darkMode) darkColorScheme() else lightColorScheme()
    val scheme = theme?.toMaterialColorScheme(base, darkMode = darkMode) ?: base

    // The one call that reads the brand name. A build that has never heard of the served brand falls
    // back to brand A's scale silently; `BrandKitsTest` is what stops that being discovered by an
    // operator instead.
    val shapes = KonektShapeScale.forBrand(theme?.id)

    // Keyed on the shape SCALE rather than on the design system built from it: `KonektDesignSystem`
    // is a plain class, so a fresh instance is unequal to the last one and the memo would miss every
    // time. `KonektShapeScale` is a data class and compares by value.
    val designSystem =
        remember(theme, shapes, darkMode) {
            val konekt = KonektDesignSystem(shapes = shapes)
            theme?.let { RemoteThemeDesignSystem(it, konekt, darkModeOverride = darkMode) } ?: konekt
        }

    // THE TYPE SCALE IS OURS, not Material's (`B-114`). kompot resolves every typography token from
    // here, so this one argument is what puts the canvas's faces, sizes and weights on every screen.
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(
            LocalKompotDesignSystem provides designSystem,
            // THE SCALE ITSELF, beside the design system built from it (`B-112`).
            //
            // A card's radius and its inset are a PAIR in the canvas — 36 with 22, 20 with 18 — and
            // the radius half is a brand's, resolved from its name. The design system answers one
            // container role, so every card resolved to `md` and the hierarchy the canvas draws was
            // flat here. `CardGeometry` needs the steps to pick from, and this is what gives them to
            // it without inventing a role vocabulary kompot does not have.
            LocalKonektShapeScale provides shapes,
            content = content,
        )
    }
}
