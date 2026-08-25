package io.konekt.client.theme

import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.theme.KompotPalette
import io.github.youndie.kompot.theme.parseArgbHex
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// What has to be true of a brand kit before an operator can be told to write one.
//
// The kits are data, and data has no compiler. Every failure this file exists for is silent by
// construction: kompot's overlay answers `resolveColor` from the theme WHEN the theme has the token
// and from the fallback when it does not, so a kit that forgets `on_primary_container` does not throw
// and does not log — it draws Material's default purple inside brand B's orange, on one control, in
// one state, and the operator finds it in a screenshot from a customer.
//
// The other half is the half B-22 exists for: **a kit names a brand, and the brand's radii are in
// this build.** A kit for a brand no scale answers for is served happily and drawn with brand A's
// shapes, which looks like a rendering bug and is a release-train problem.
class BrandKitsTest {
    private val tokens = M3Colors.all.map { it.key }

    @Test
    fun `the guard is looking at something`() {
        // Without this the whole file passes on an empty directory — which is exactly what a moved
        // working directory or a renamed resource folder would produce.
        assertTrue(
            BrandKits.files.size >= 2,
            "found ${BrandKits.files.size} brand kits in ${BrandKits.directory} — a second brand is the point of B-22",
        )
        assertEquals(20, tokens.size, "M3Colors grew or shrank; the completeness check below is measured against it")
    }

    @Test
    fun `every kit names itself after the file it is served from`() {
        BrandKits.files.forEach { file ->
            val kit = BrandKits.kits().getValue(file.nameWithoutExtension)
            // A kit copied to start the next brand and never renamed inside serves brand A under the
            // name brand B: the colours are wrong AND the client picks the wrong shape scale, because
            // the scale is resolved from `id` rather than from the file name.
            assertEquals(file.nameWithoutExtension, kit.id, "${file.fileName} carries the id '${kit.id}'")
        }
    }

    @Test
    fun `every kit answers for every colour token this build can resolve`() {
        BrandKits.kits().forEach { (name, kit) ->
            listOf("light" to kit.light, "dark" to kit.dark).forEach { (mode, palette) ->
                // `dark = null` is legal in the toolkit and means "stay on the built-in palette".
                // konekt does not use that: an ink brand that reverts to a teal dark mode is a brand
                // that only half arrived.
                val colors = assertNotNull(palette, "$name has no $mode palette").colors
                val missing = tokens - colors.keys

                assertContentEquals(
                    emptyList<String>(),
                    missing.sorted(),
                    "$name/$mode leaves ${missing.size} tokens to the client's built-in palette: ${missing.sorted()}",
                )
            }
        }
    }

    @Test
    fun `every colour in every kit parses`() {
        BrandKits.kits().forEach { (name, kit) ->
            listOf("light" to kit.light, "dark" to kit.dark).forEach { (mode, palette) ->
                assertNotNull(palette, "$name has no $mode palette").colors.forEach { (token, value) ->
                    // A hex the toolkit cannot read is not an error either: `argbFor` answers null and
                    // the overlay falls through to the fallback, so one mistyped digit is one control
                    // in the wrong colour and nothing else.
                    assertNotNull(parseArgbHex(value), "$name/$mode: '$token' is '$value', which kompot cannot read")
                }
            }
        }
    }

    @Test
    fun `the brands differ, so switching between them is not a no-op`() {
        val kits = BrandKits.kits()
        val a = kits.getValue("brand-a")
        val b = kits.getValue("brand-b")

        // Stated per palette rather than on the whole object: two kits that differ only in `id` would
        // satisfy `assertNotEquals(a, b)` and repaint nothing, which is the vacuous version of every
        // demonstration in `BrandSwitchTest`.
        assertNotEquals(a.light.colorsOrEmpty(), b.light.colorsOrEmpty(), "the two light palettes are identical")
        assertNotEquals(a.dark.colorsOrEmpty(), b.dark.colorsOrEmpty(), "the two dark palettes are identical")
    }

    @Test
    fun `every brand the server ships has a shape scale in this build`() {
        val served =
            BrandKits
                .kits()
                .values
                .map { it.id }
                .toSet()

        // BOTH DIRECTIONS, and the second is not symmetry for its own sake. A served brand with no
        // scale is drawn with brand A's radii and nobody is told; a scale for a brand nobody serves is
        // dead weight that reads like a supported brand to the next person choosing one.
        assertContentEquals(
            emptyList<String>(),
            (served - KonektShapeScale.byBrand.keys).sorted(),
            "the server ships kits this build has no radii for, and they will silently be drawn as brand A",
        )
        assertContentEquals(
            emptyList<String>(),
            (KonektShapeScale.byBrand.keys - served).sorted(),
            "this build carries radii for brands the server ships no kit for",
        )
    }

    @Test
    fun `the two shape scales are the ones the canvas fixes`() {
        // Written out rather than compared to a constant, because the constant is the thing under
        // test. The canvas says: A is lg 36 / md 20 / sm 12 with pills; B is 22 / 12 / 8 with rounded
        // rectangles (design-app-canvas, "Typography and shape").
        assertEquals(KonektShapeScale(36.dp, 20.dp, 12.dp, pillButtons = true), KonektShapeScale.BrandA)
        assertEquals(KonektShapeScale(22.dp, 12.dp, 8.dp, pillButtons = false), KonektShapeScale.BrandB)
    }

    @Test
    fun `an unknown brand falls back rather than failing`() {
        assertEquals(KonektShapeScale.BrandB, KonektShapeScale.forBrand("brand-b"))
        assertEquals(KonektShapeScale.BrandA, KonektShapeScale.forBrand("brand-a"))
        // Both the unknown name and the absent one, because they arrive by different routes: a brand
        // this build predates, and the state before `/theme` has answered at all.
        assertEquals(KonektShapeScale.BrandA, KonektShapeScale.forBrand("brand-z"))
        assertEquals(KonektShapeScale.BrandA, KonektShapeScale.forBrand(null))
    }
}

private fun KompotPalette?.colorsOrEmpty(): Map<String, String> = this?.colors ?: emptyMap()
