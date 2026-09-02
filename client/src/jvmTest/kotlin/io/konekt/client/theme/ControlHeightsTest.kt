package io.konekt.client.theme

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.client.render.konektRegistry
import io.konekt.components.ButtonEmphasis
import kotlin.test.Test
import kotlin.test.assertTrue

// THE CANVAS'S HEIGHTS THROUGH THE REAL PATH: the registry's button renderer, under `KonektTheme`,
// measured. kompot#106 gave a surface a `minHeight`; this is what says the pill is 56 on screen and
// not only in the design system's constants — a golden of a 40-point button beside a constant that
// says 56 is exactly the frame that was recorded first.
@OptIn(ExperimentalTestApi::class)
class ControlHeightsTest {
    private fun button(variant: String?) =
        ButtonComponent(id = "b", text = "Press", action = NavigateAction("app://nowhere"), variant = variant)

    private fun heightOf(variant: String?): Float {
        var height = 0f
        runComposeUiTest {
            setContent {
                KonektTheme(theme = null, darkMode = false) {
                    konektRegistry().RenderNode(
                        component = button(variant),
                        actionHandler = KompotActionHandler {},
                        formController = FormController(FormSchema(formId = "t", fields = emptyList())),
                    )
                }
            }
            height = onNodeWithText("Press").getUnclippedBoundsInRoot().height.value
        }
        return height
    }

    @Test
    fun `a pill is as tall as the canvas says`() {
        listOf(null, ButtonEmphasis.PRIMARY, ButtonEmphasis.QUIET, ButtonEmphasis.TONAL).forEach { variant ->
            val height = heightOf(variant)
            assertTrue(height >= KonektDesignSystem.PILL_HEIGHT.value, "a '$variant' button is $height tall, not 56")
        }
    }

    @Test
    fun `a text control is a row, not a pill`() {
        val height = heightOf(ButtonEmphasis.LINK)
        assertTrue(
            height >= KonektDesignSystem.ROW_HEIGHT.value && height < KonektDesignSystem.PILL_HEIGHT.value,
            "a link is $height tall",
        )
    }
}
