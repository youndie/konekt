package io.konekt.screenshots

import io.github.youndie.kompot.ColorToken
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.theme.KompotTheme
import io.github.youndie.kompot.theme.parseArgbHex
import io.konekt.client.theme.BrandKits
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// WHAT THE GOLDENS SHOW, ASSERTED. Not that they exist, and not that they are the same as last time.
//
// A headless capture succeeds on broken content: a valid PNG of the right size showing nothing at all
// is still a valid PNG, and `viddikVerify` would then compare one photograph of nothing against
// another photograph of nothing, for ever, green. Every check here therefore reads INSIDE the file,
// and each is tied to something the product decided:
//
//   * the accent colour of a counter state is looked up in **the kit the server actually ships**, so
//     "low is amber" is not this test's opinion but the served palette's;
//   * `Counter - Unknown state` must be pixel-identical to `Counter - Normal`, which is the whole
//     degradation rule of an open string on the wire, drawn;
//   * a light frame must contain no colour that exists only in the DARK palette. That one is here
//     because the first recording of these goldens failed it: `KonektTheme` resolved tokens through
//     the toolkit's convenience wrapper, which asks `isSystemInDarkTheme()` — the HOST's appearance —
//     so a light screen came out with a dark card on a dark machine, and the same goldens would have
//     been unreproducible anywhere else.
//
// Numbers quoted in the assertions were measured on these goldens, not chosen.
class GoldenContentTest {
    private val brandA: KompotTheme = BrandKits.kits().getValue("brand-a")
    private val brandB: KompotTheme = BrandKits.kits().getValue("brand-b")

    @Test
    fun `every golden shows something`() {
        val goldens = snapshotsDirectory.toFile().listFiles { file -> file.name.endsWith(".png") }.orEmpty()
        assertTrue(goldens.isNotEmpty(), "there are no goldens to inspect")

        goldens.forEach { file ->
            val image = ImageIO.read(file) ?: fail("${file.name} is not a readable image")
            val pixels = image.pixels()
            val opaque = pixels.count { (it ushr 24 and 0xFF) == 0xFF }
            val distinct = pixels.toSet().size

            // A blank capture is transparent; a capture of a single failed layer is one flat colour.
            // Measured on the committed set: 51%-62% opaque and 348-762 distinct colours.
            assertTrue(
                opaque > pixels.size / 10,
                "${file.name} is ${opaque * 100 / pixels.size}% opaque — that is a photograph of an " +
                    "empty frame, not of a screen",
            )
            assertTrue(
                distinct > MINIMUM_DISTINCT_COLOURS,
                "${file.name} contains only $distinct distinct colours, so nothing with text or a " +
                    "border was drawn in it",
            )
        }
    }

    @Test
    fun `each counter state is drawn in the accent role its state names`() {
        // The three roles the renderer maps the three states onto, resolved through the served kit.
        // Asserting the colour is PRESENT and the other two are ABSENT is what makes this a statement
        // about the state rather than about the frame being different from its neighbour.
        val primary = brandA.light(M3Colors.Primary)
        val secondary = brandA.light(M3Colors.Secondary)
        val error = brandA.light(M3Colors.Error)

        assertAccent("Counter_Normal.png", present = primary, absent = listOf(secondary, error))
        assertAccent("Counter_Low.png", present = secondary, absent = listOf(primary, error))
        assertAccent("Counter_Exhausted.png", present = error, absent = listOf(primary, secondary))
    }

    @Test
    fun `an unknown state on the wire draws the ordinary card and nothing of its own`() {
        val normal = golden("Counter_Normal.png")
        val unknown = golden("Counter_Unknown_state.png")

        // Pixel-identical, not merely similar. `state` is an open string precisely so a server one
        // release ahead can name something this build does not know, and the rule is that such a word
        // draws the ORDINARY card — not an error colour, not a blank, not a guess. The rule lives in
        // one `else` branch of `accentToken()`, which is exactly the kind of line a refactor turns
        // into `error("unknown state")` with nothing objecting.
        assertEquals(
            0,
            difference(normal, unknown).repainted,
            "the card drawn for the unknown state \"$UNKNOWN_COUNTER_STATE\" differs from the ordinary " +
                "card. A state this build has never heard of must degrade to the neutral form",
        )
    }

    @Test
    fun `the brand pair differs in geometry and not only in colour`() {
        val difference = difference(golden("Brand_A.png"), golden("Brand_B.png"))

        // Alpha is the silhouette: a corner radius changing decides which pixels are COVERED, while a
        // repaint of a covered pixel does not. Measured on the committed pair: 796 pixels change
        // coverage and 71371 change colour. The 796 is the whole point of the shape scale being a
        // build constant that actually reaches the screen — without it this pair would only prove
        // that two palettes are different.
        assertTrue(
            difference.moved > 0,
            "brand A and brand B drew the same silhouette. Either the shape scale is not reaching the " +
                "surfaces, or every control in the frame is too short for its radius to survive " +
                "RoundedCornerShape's clamp — see docs/design/design-brand-kit.md",
        )
        assertTrue(difference.repainted > difference.moved, "the two brands did not repaint")
    }

    @Test
    fun `dark mode repaints the frame and moves nothing in it`() {
        // The counterpart claim, and the reason the canvas draws light and dark side by side: dark is
        // a palette, not a layout. Measured: 0 pixels change coverage, 71187 change colour.
        val difference = difference(golden("Brand_A.png"), golden("Brand_A_Dark.png"))

        assertEquals(
            0,
            difference.moved,
            "${difference.moved} pixels changed coverage between the light and dark frames of one " +
                "brand, so something about the LAYOUT depends on dark mode",
        )
        assertTrue(difference.repainted > 0, "the dark frame is the light frame — dark mode did nothing")
    }

    @Test
    fun `a light frame carries no colour from the dark palette, and the other way round`() {
        // THE GUARD FOR THE DEFECT THIS ITEM FOUND. `KonektTheme` builds the Material scheme from the
        // `darkMode` it is given and used to resolve every ColorToken through `isSystemInDarkTheme()`
        // instead — so on a machine set to dark, the light frame came out with brand A's DARK
        // `surface_variant` (#18211F) under a button painted from the LIGHT `primary` (#0B6B60).
        //
        // Stated as palette membership rather than as a sampled pixel, so it survives the layout
        // moving. Only colours EXCLUSIVE to one palette count: the two kits share a few values, and a
        // shared value proves nothing either way.
        assertPaletteHalf("Brand_A.png", brandA, darkFrame = false)
        assertPaletteHalf("Brand_A_Dark.png", brandA, darkFrame = true)
        assertPaletteHalf("Brand_B.png", brandB, darkFrame = false)
        assertPaletteHalf("Brand_B_Dark.png", brandB, darkFrame = true)
    }

    private fun assertAccent(
        name: String,
        present: Int,
        absent: List<Int>,
    ) {
        val colours = golden(name).pixels().toSet()

        assertTrue(
            present in colours,
            "$name does not contain ${present.hex()}, the accent this state resolves to in the kit " +
                "the server ships",
        )
        absent.forEach {
            assertTrue(
                it !in colours,
                "$name contains ${it.hex()}, which belongs to a different counter state",
            )
        }
    }

    private fun assertPaletteHalf(
        name: String,
        kit: KompotTheme,
        darkFrame: Boolean,
    ) {
        val wanted = kit.palette(darkFrame)
        val other = kit.palette(!darkFrame)
        val exclusiveToTheOther = other - wanted
        val colours = golden(name).pixels().toSet()
        val trespassing = colours intersect exclusiveToTheOther

        assertTrue(
            trespassing.isEmpty(),
            "$name is the ${if (darkFrame) "DARK" else "LIGHT"} frame and contains " +
                "${trespassing.map { it.hex() }} — colours that exist only in the other half of the " +
                "kit. The Material scheme and the token lookups are disagreeing about dark mode",
        )
    }

    private fun KompotTheme.light(token: ColorToken): Int =
        colorFor(token, darkMode = false) ?: fail("$id has no ${token.key} in its light palette")

    private fun KompotTheme.palette(darkMode: Boolean): Set<Int> =
        (paletteFor(darkMode) ?: fail("$id has no ${if (darkMode) "dark" else "light"} palette"))
            .colors
            .values
            .mapNotNull(::parseArgbHex)
            .toSet()

    private data class Difference(
        val repainted: Int,
        val moved: Int,
    )

    private fun difference(
        before: BufferedImage,
        after: BufferedImage,
    ): Difference {
        assertEquals(before.width to before.height, after.width to after.height, "the frames are different sizes")
        val first = before.pixels()
        val second = after.pixels()
        var repainted = 0
        var moved = 0

        first.indices.forEach { index ->
            if (first[index] != second[index]) repainted++
            if ((first[index] ushr 24) != (second[index] ushr 24)) moved++
        }

        return Difference(repainted = repainted, moved = moved)
    }

    private fun golden(name: String): BufferedImage {
        val file = snapshotsDirectory.resolve(name).toFile()
        if (!file.exists()) fail("no golden at $file — run `LOCAL=1 ./gradlew :client:viddikRecord`")
        return ImageIO.read(file) ?: fail("$file is not a readable image")
    }

    private fun BufferedImage.pixels(): IntArray = getRGB(0, 0, width, height, null, 0, width)

    private fun Int.hex(): String = "#%08X".format(this)

    private companion object {
        // Below this a frame is a flat rectangle rather than a screen. The committed set runs
        // 348-762; anti-aliased text alone accounts for most of it.
        const val MINIMUM_DISTINCT_COLOURS = 64

        val snapshotsDirectory: Path by lazy {
            var candidate = Path("").absolute()
            while (!candidate.resolve("settings.gradle.kts").exists()) {
                candidate = candidate.parent ?: fail("no settings.gradle.kts above ${Path("").absolute()}")
            }

            candidate.resolve("client/src/jvmTest/snapshots").also {
                if (!it.isDirectory()) fail("the goldens are not at $it")
            }
        }
    }
}
