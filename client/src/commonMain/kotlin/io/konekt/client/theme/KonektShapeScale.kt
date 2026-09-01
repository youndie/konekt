package io.konekt.client.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The corner radii of one brand.
//
// THEY LIVE IN THE CLIENT AND NOT ON THE WIRE, and that is a decision rather than an omission:
// `kompot-core` declares `ColorToken` and `TypographyToken` and nothing for shape, the modifier
// vocabulary carries no radius, and a `SurfaceRole` is documented as a client-side key that never
// travels (research-architecture §1.2, D2). So a brand's radii are a client build constant. A server
// that could name a radius would be a server that can make a control unreachable by rounding it away.
//
// The consequence an operator has to be told in words rather than left to discover: a colour change
// is a deploy of the server, a shape change is a release of the application. See
// docs/design/design-brand-kit.md.
data class KonektShapeScale(
    val large: Dp,
    val medium: Dp,
    val small: Dp,
    // A pill is not a radius. `RoundedCornerShape(percent = 50)` follows the height of whatever it
    // wraps, and writing it as a large Dp gives a shape that is a pill at one size and a rounded
    // rectangle at another.
    val pillButtons: Boolean,
) {
    val largeShape: Shape get() = RoundedCornerShape(large)

    // `large` USED TO BE DRAWN BY NOTHING ON A PILL BRAND, and since `B-112` it is drawn by every
    // headline card on every screen.
    //
    // What was here said so as a fact: `largeShape` was read by `buttonShape` alone and only when
    // pills were off, so brand A stated a 36 that nothing rendered — measured, by setting it to 8 and
    // watching no golden move. `InertRadiusIsDeclaredTest` held that in both directions and told the
    // next reader what to do if it ever stopped being true: reinstate `B-28`'s second acceptance
    // criterion rather than relax the line.
    //
    // It stopped being true. `CardGeometry.Tier.CARD` resolves to `largeShape`, because the canvas
    // pairs its headline blocks with `lg` — so brand A's 36 reaches the balance block, the allowance
    // block and a package's card, and a mutation of it now moves brand A's goldens and only theirs.
    //
    // The property that reported the inertia is gone with it: every step of this scale is drawn now,
    // and a boolean answering "is it?" would always say yes.
    val mediumShape: Shape get() = RoundedCornerShape(medium)
    val smallShape: Shape get() = RoundedCornerShape(small)
    val buttonShape: Shape get() = if (pillButtons) PILL_SHAPE else largeShape

    companion object {
        // Brand A, from the design canvas: lg 36 / md 20 / sm 12, with pills.
        val BrandA = KonektShapeScale(large = 36.dp, medium = 20.dp, small = 12.dp, pillButtons = true)

        // Brand B, from section 08 of the same canvas: 22 / 12 / 8, and rounded rectangles instead of
        // pills. The section exists to prove the layout survives the shape swap — nothing in the
        // markup depends on the radius — which is what makes a client-side shape constant cheap
        // rather than dangerous.
        val BrandB = KonektShapeScale(large = 22.dp, medium = 12.dp, small = 8.dp, pillButtons = false)

        // The brand name a served theme carries, resolved against what this build was compiled with.
        //
        // THIS READS `KompotTheme.id`, WHICH THE TOOLKIT DOCUMENTS AS DIAGNOSTICS-ONLY. konekt is
        // using it for a purpose kompot does not promise, and the alternative — a shape token of our
        // own on the wire — needs a fork of `kompot-core` and destroys the exact property this
        // project exists to demonstrate. The shape of the resolution is the same as
        // `ColorToken("promo_gold")`: the server sends a NAME and the client decides what it means.
        // If a future kompot drops or repurposes `id`, `brandsWithAShapeScale` is the one place to
        // change.
        val byBrand: Map<String, KonektShapeScale> =
            mapOf(
                "brand-a" to BrandA,
                "brand-b" to BrandB,
            )

        // A brand this build has never heard of gets brand A's scale, and gets it SILENTLY — there is
        // no sensible alternative, because refusing to draw a screen over a radius is worse than
        // drawing it with the wrong one. What makes that safe is not the fallback: it is
        // `BrandKitsTest`, which fails when the server ships a kit no scale answers for.
        fun forBrand(brandId: String?): KonektShapeScale = byBrand[brandId] ?: BrandA

        // Named rather than private: the guard that keeps `large` honestly inert has
        // to be able to say what a pill IS, and a test asserting a shape it cannot name would be
        // asserting a literal beside the one it is checking.
        val PILL_SHAPE: Shape = RoundedCornerShape(percent = 50)
    }
}

// THE BRAND'S SCALE, where a renderer can reach it. `LocalKompotDesignSystem` answers ONE container
// role, so it cannot express the tiers the canvas draws; this can.
//
// DEFAULTS TO BRAND A, which is the same silent fallback `forBrand` already makes for a brand this
// build has never heard of. A composable drawn outside `KonektTheme` — a preview, a fixture that
// builds its own kit — gets brand A's radii rather than none.
val LocalKonektShapeScale = staticCompositionLocalOf { KonektShapeScale.BrandA }
