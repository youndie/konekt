package io.konekt.client.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
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
}
