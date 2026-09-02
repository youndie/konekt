package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.konekt.client.theme.KonektDesignSystem
import io.konekt.components.EsimQrComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The activation code as a picture, and the picture is made HERE.
//
// The component carries the `LPA:1$…` string and nothing else, because an image would need a URL and
// a URL puts a credential into a query string and an access log. So what is checked is that the
// string turns into something a camera could read, and that when it cannot the screen still hands
// the subscriber the code in words.
@OptIn(ExperimentalTestApi::class)
class EsimQrRendererTest {
    private val activationCode = "LPA:1\$rsp.konekt.io\$8F214C90"

    private fun render(component: EsimQrComponent) =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalKompotDesignSystem provides KonektDesignSystem(),
                        LocalKompotRegistry provides konektRegistry(),
                    ) {
                        EsimQrRenderer().Render(
                            component = component,
                            actionHandler = KompotActionHandler { },
                            formController = FormController(FormSchema(formId = "qr", fields = emptyList())),
                        )
                    }
                }
            }

            component.captionText?.let { onNodeWithText(it).assertExists() }
            component.manualCodeText?.let { onNodeWithText(it).assertExists() }
        }

    @Test
    fun `the code renders with its caption and its typed fallback`() {
        render(
            EsimQrComponent(
                id = "esim-qr",
                payload = activationCode,
                captionText = "Stay on Wi-Fi. This takes up to a minute and finishes on its own.",
                manualCodeText = "8F21-4C90",
            ),
        )
    }

    @Test
    fun `the encoding is a square matrix of the size a QR actually has`() {
        val modules = encodeForTest(activationCode)

        assertTrue(modules.isNotEmpty(), "the activation code encoded to nothing")
        // Version 1 is 21×21 and every version adds four; anything else is not a QR symbol.
        assertTrue(modules.size >= 21 && (modules.size - 21) % 4 == 0, "not a QR size: ${modules.size}")
        modules.forEach { row -> assertEquals(modules.size, row.size, "the matrix is not square") }
    }

    @Test
    fun `the three finder patterns are where a scanner looks for them`() {
        val modules = encodeForTest(activationCode)
        val last = modules.size - 1

        // The 7×7 eyes in three corners — dark border, light ring, dark core. Checking the corners
        // rather than a byte count is what tells a real symbol from a plausible-looking grid: a
        // matrix that encoded the wrong thing still has the right dimensions.
        listOf(0 to 0, 0 to last - 6, last - 6 to 0).forEach { (row, column) ->
            assertTrue(modules[row][column], "no finder pattern at ($row, $column)")
            assertTrue(modules[row + 1][column + 1] == false, "the finder's light ring is missing at ($row, $column)")
            assertTrue(modules[row + 3][column + 3], "the finder's core is missing at ($row, $column)")
        }

        // And the TIMING PATTERN, which is the other thing a scanner locks onto: row six alternates
        // dark and light across the whole symbol, starting dark.
        //
        // The first version of this test tried to prove the fourth corner has NO finder by reading
        // two pixels — which a data module satisfies or fails at random. An absence is not provable
        // by poking at it; an invariant that must hold is.
        (8 until last - 7).forEach { column ->
            assertEquals(column % 2 == 0, modules[6][column], "the timing pattern breaks at column $column")
        }
    }

    @Test
    fun `a payload too large to encode leaves the typed code rather than throwing`() {
        // A QR tops out around 2 900 alphanumerics. The renderer answers an empty matrix and draws
        // the caption and the manual code — which is what `manualCodeText` is carried for, and the
        // reason the failure is a blank square rather than a crash on somebody's install screen.
        val modules = encodeForTest("X".repeat(10_000))
        assertTrue(modules.isEmpty(), "an unencodable payload produced a matrix")

        render(
            EsimQrComponent(
                id = "esim-qr",
                payload = "X".repeat(10_000),
                captionText = "We could not draw this code.",
                manualCodeText = "8F21-4C90",
            ),
        )
    }

    // THE TILE IS LIGHT ON A DARK PAGE (`B-115`). The modules were black and the quiet zone was
    // padding over whatever the page was — near-black in dark mode, so the code was black on black
    // and no camera read it. The page here is painted the dark ground on purpose: a test on the
    // harness's white window would pass with or without the tile, which is how the frame went out.
    @Test
    fun `the code sits on a light tile whatever the page is`() =
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    CompositionLocalProvider(
                        LocalKompotDesignSystem provides KonektDesignSystem(),
                        LocalKompotRegistry provides konektRegistry(),
                    ) {
                        Box(Modifier.size(400.dp).background(DARK_PAGE).testTag("page")) {
                            EsimQrRenderer().Render(
                                component = EsimQrComponent(id = "qr", payload = activationCode),
                                actionHandler = KompotActionHandler { },
                                formController = FormController(FormSchema(formId = "qr", fields = emptyList())),
                            )
                        }
                    }
                }
            }

            val image = onNodeWithTag("page").captureToImage()
            val pixels = image.toPixelMap()
            // The tile: 0.7 of the 400-point box, centred at the top.
            val tile = (image.width * EsimQrRenderer.QR_WIDTH_FRACTION).toInt()
            val left = (image.width - tile) / 2
            val quiet = EsimQrRenderer.QUIET_ZONE_DP * image.width / 400 / 2

            // Every pixel of the quiet zone's top edge is light — that is the zone a scanner needs.
            val zone = (left + quiet until left + tile - quiet step 4).map { x -> pixels[x, quiet] }
            assertTrue(zone.isNotEmpty(), "the tile could not be located, so nothing here was checked")
            zone.forEach { assertTrue(it.luminance() > 0.8f, "a quiet-zone pixel is dark: $it") }

            // And the finder pattern in the corner is dark — the tile did not paint the code away.
            val inside = (left + quiet * 2 + 2 until left + tile / 3).map { x -> pixels[x, quiet * 2 + 2] }
            assertTrue(inside.any { it.luminance() < 0.2f }, "no dark module found inside the tile")
        }

    private companion object {
        val DARK_PAGE = Color(0xFF0F1614)
    }
}
