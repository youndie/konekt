package io.konekt.client.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A NUMBER THE CANVAS STATES AND THE BUILD CANNOT DRAW, held as a fact rather than left as a surprise.
//
// `largeShape` is read by `buttonShape` and only when pills are off, so a brand with pills states a
// large radius nothing renders. Brand A's `lg` is 36 and setting it to 8 was measured to change none
// of the goldens.
//
// This test exists so that stays a DECISION. Without it there are two ways for the next person to
// "fix" the silence, and both are wrong: wire `lg` to some surface so a golden finally bites — which
// designs a product surface to satisfy a test — or delete `large` from the scale, which takes away a
// radius brand B genuinely draws.
class InertRadiusIsDeclaredTest {
    @Test
    fun `a brand with pills draws no large radius, and says so`() {
        assertTrue(KonektShapeScale.BrandA.pillButtons, "brand A is the pill brand; this test is about that")
        assertFalse(
            KonektShapeScale.BrandA.largeIsDrawn,
            "brand A claims to draw its large radius — if that is now true, B-28's second acceptance " +
                "criterion has become satisfiable and should be reinstated rather than this line relaxed",
        )

        // The pill is not the large shape wearing a different name: it follows the height of what it
        // wraps, which is why writing it as a large Dp gives a pill at one size and a rounded
        // rectangle at another.
        assertEquals(KonektShapeScale.PILL_SHAPE, KonektShapeScale.BrandA.buttonShape)
    }

    @Test
    fun `a brand without pills does draw it, which is what keeps the number in the scale`() {
        assertFalse(KonektShapeScale.BrandB.pillButtons)
        assertTrue(
            KonektShapeScale.BrandB.largeIsDrawn,
            "no brand draws the large radius, so the scale is carrying a number nothing reads",
        )
        assertEquals(KonektShapeScale.BrandB.largeShape, KonektShapeScale.BrandB.buttonShape)
    }
}
