package io.konekt.client.theme

import io.konekt.client.render.CardGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// EVERY NUMBER IN THE SCALE REACHES A SCREEN, which is the mirror of the test this replaces.
//
// The guard this replaces held the opposite as a fact (named in `B-112`, and deleted with it): `large` was read by `buttonShape` alone and
// only when pills were off, so brand A stated a 36 that nothing drew. It said what to do if that ever
// changed — reinstate `B-28`'s second acceptance criterion rather than relax the line — and `B-112` is
// what changed it: the canvas pairs its headline blocks with `lg`, so `CardGeometry.Tier.CARD` takes
// `largeShape` and brand A's 36 is now on the balance block, the allowance block and a package's card.
//
// So the fact worth holding is the other one: NO STEP OF THIS SCALE IS INERT. A number in a brand's
// scale that nothing draws is a number nobody can be held to, and this build has had one for two
// seasons without noticing.
class EveryStepOfTheScaleIsDrawnTest {
    @Test
    fun `each tier takes a different step, so a scale cannot be flat`() {
        // The mapping, asserted where it is decided rather than by reading a golden: a headline block
        // is `lg` and a list item is `md`. If these ever became the same step, every screen would go
        // back to one radius and no golden would say which of the two was wrong.
        assertEquals(
            CardGeometry.Tier.entries.size,
            CardGeometry.Tier.entries
                .map { it.inset }
                .distinct()
                .size,
            "two tiers share an inset, so the scale the canvas draws is flattened here",
        )
    }

    @Test
    fun `both brands declare three distinct radii, and every one of them is a tier`() {
        listOf("brand A" to KonektShapeScale.BrandA, "brand B" to KonektShapeScale.BrandB)
            .forEach { (name, scale) ->
                assertEquals(
                    3,
                    listOf(scale.large, scale.medium, scale.small).distinct().size,
                    "$name states a scale with a repeated step, so one of its numbers cannot be seen",
                )

                // `sm` is the field radius, `md` the item, `lg` the headline card — the three tiers
                // the canvas's token block declares (`sm 12/8 | md 20/12 | lg 36/22`).
                assertTrue(
                    scale.large > scale.medium && scale.medium > scale.small,
                    "$name's scale is not ordered, so a bigger block would round less than a smaller one",
                )
            }
    }

    // AND THE PILL IS STILL NOT THE LARGE SHAPE, which was the other half of the test this replaces
    // and is unaffected: a pill follows the height of what it wraps, so writing it as a large Dp gives
    // a pill at one size and a rounded rectangle at another.
    @Test
    fun `a pill brand still draws a pill on its buttons`() {
        assertTrue(KonektShapeScale.BrandA.pillButtons)
        assertEquals(KonektShapeScale.PILL_SHAPE, KonektShapeScale.BrandA.buttonShape)
        assertEquals(KonektShapeScale.BrandB.largeShape, KonektShapeScale.BrandB.buttonShape)
    }
}
