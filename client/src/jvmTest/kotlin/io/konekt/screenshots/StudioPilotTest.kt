package io.konekt.screenshots

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.studio.KompotStudioScreen
import io.konekt.client.net.konektClientJson
import ru.workinprogress.viddik.LocalViddikDarkTheme
import ru.workinprogress.viddik.core.ImageDiffer
import ru.workinprogress.viddik.core.captureComposable
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// THE PILOT (kompot B-14): does the studio photograph the client this repository ships?
//
// Every other assertion about the studio lives in the toolkit and is checked against the toolkit's own
// seven component types, one stock palette and no client. This build has fourteen types, two served
// brand kits, a client-side shape scale the wire cannot carry, and — decisively — four goldens
// recorded by `viddikVerify` from the application's own composition root.
//
// So the question has an oracle here and nowhere else: take the same tree through the STUDIO and diff
// it against the picture the SCREENSHOT SUITE recorded. If they differ, the studio is a picture of a
// second client, and every screen anybody previews in it is a screen nobody ships.
class StudioPilotTest {
    private val config = studioConfig()

    private val body: String get() = konektClientJson.encodeKompotComponent(brandShowcaseTree())

    @Test
    fun `the studio draws the frame the screenshot suite recorded, in both brands and both themes`() {
        val differences =
            listOf("brand-a" to false, "brand-a" to true, "brand-b" to false, "brand-b" to true)
                .map { (brand, dark) -> brand to dark }
                .associateWith { (brand, dark) -> compare(brand, dark) }

        val failures = differences.filterValues { it > TOLERANCE_PERCENT }

        assertTrue(
            failures.isEmpty(),
            "the studio's frame differs from the golden the screenshot suite recorded: " +
                failures.entries.joinToString { (case, percent) -> "${case.first} dark=${case.second}: $percent%" },
        )

        // Recorded rather than only asserted: "0.00% everywhere" is the interesting number, and a
        // reader of a green run otherwise has to take the tolerance's word for it.
        println("studio pilot: " + differences.entries.joinToString { (case, percent) -> "${case.first}/dark=${case.second}=$percent%" })
    }

    @Test
    fun `the comparison can fail, which is what makes the one above mean anything`() {
        // THE POSITIVE CONTROL. Every assertion above is satisfied by a `compare` that always answers
        // zero — a capture that silently drew nothing against a golden that is mostly transparent
        // would do exactly that. So one case is compared against the WRONG golden, and it has to
        // exceed the tolerance.
        val goldenOfB = config.snapshotsDirectory!!.resolve(config.goldenName("brand-b", false, "screen"))
        val golden: BufferedImage = ImageIO.read(goldenOfB.toFile())

        val frameOfA =
            captureComposable(
                width = golden.width,
                height = golden.height,
                compositionLocals = listOf(LocalViddikDarkTheme provides false),
            ) {
                KompotStudioScreen(config = config, body = body, brand = "brand-a", dark = false)
            }

        val mismatch = ImageDiffer.diff(golden, frameOfA).mismatchPercent
        assertTrue(
            mismatch > TOLERANCE_PERCENT,
            "brand A's frame matched brand B's golden to $mismatch% — the comparison is measuring nothing",
        )
    }

    private fun compare(
        brand: String,
        dark: Boolean,
    ): Double {
        val goldenPath = config.snapshotsDirectory!!.resolve(config.goldenName(brand, dark, "screen"))
        if (!goldenPath.exists()) fail("no golden at $goldenPath — the pilot would be comparing with nothing")

        val golden: BufferedImage = ImageIO.read(goldenPath.toFile())

        // The size the golden was recorded at, and the composition local viddik itself provides when
        // it draws a dark variant: the studio has to be handed the SAME frame, or the difference
        // measured would be the harness's rather than the studio's.
        val actual =
            captureComposable(
                width = golden.width,
                height = golden.height,
                compositionLocals = listOf(LocalViddikDarkTheme provides dark),
            ) {
                KompotStudioScreen(config = config, body = body, brand = brand, dark = dark)
            }

        return ImageDiffer.diff(golden, actual).mismatchPercent
    }

    private companion object {
        // viddik's own default for these cases: the goldens in this package are recorded and verified
        // at it, so anything the studio adds beyond it is a real difference rather than rasterisation.
        const val TOLERANCE_PERCENT = 0.1
    }
}

// The very tree the goldens are of, as the wire body a server would send. One list, two readers: the
// screenshot fixture composes it, the studio decodes it.
private fun brandShowcaseTree(): KompotComponent = treeComponent(brandShowcaseComponents())
