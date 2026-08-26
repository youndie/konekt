package io.konekt.client.theme

import androidx.compose.foundation.shape.RoundedCornerShape
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

    // WHETHER `large` IS DRAWN AT ALL, and it is a property of the scale rather than of a screen.
    //
    // `largeShape` is read by exactly one thing — `buttonShape`, and only when pills are off. So a
    // brand with `pillButtons = true` states a large radius that nothing in this build can draw:
    // brand A's 36 was measured to change nothing on any screen when set to 8.
    //
    // That is not a defect and it is not a gap waiting for a surface. It is what a pill MEANS: a
    // shape that follows the height of what it wraps has no radius to take from a scale. Saying so in
    // the type is what stops the next reader from either wiring `lg` somewhere to make a golden bite,
    // or deleting a number brand B genuinely draws.
    //
    // B-28 asked for a mutation of brand A's `lg` to fail brand A's goldens. It cannot, and the
    // premise is what is wrong: the property that acceptance exists for — a brand's radius reaching
    // the screen — is held through `md`, which both brands draw, and through brand B's `lg`, which is
    // drawn because brand B turns pills off.
    val largeIsDrawn: Boolean get() = !pillButtons
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
