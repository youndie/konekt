package io.konekt.client.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.ColumnRenderer
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.client.theme.KonektDesignSystem
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.konekt.components.konektWireNames
import kotlinx.serialization.SerialName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Which of konekt's nine types this client can draw, stated rather than implied.
//
// A missing renderer is NOT a defect — the type degrades to the unknown-component block, which is the
// whole bargain the additive dictionary rests on. What would be a defect is nobody knowing which
// eight are missing, so this test holds the two lists apart on purpose: adding a component without a
// renderer fails here until somebody writes the name into `notYetRendered`, and writing a renderer
// without removing the name fails too.
//
// It is a second backlog, in other words, and it lives beside the code rather than in a document
// that would be right on the day it was written.
class KonektRendererCoverageTest {
    // Every wire type this build can draw. One entry, and the list grows one screen at a time.
    private val rendered = setOf("usage_counter_card", "esim_qr")

    // The rest, each waiting for the screen that needs it. B-05 is the block a client draws instead.
    private val notYetRendered =
        setOf(
            "plan_card",
            "esim_card",
            "order_row",
            "banner",
            "snackbar",
            "step_meter",
            "skeleton",
        )

    // The toolkit's placeholder, and NOT a tenth entry in konekt's dictionary. `UnknownComponent` has
    // no @SerialName because it is never sent — it is what a decode produces for a type nobody knows —
    // and konekt registers a renderer for it to replace the toolkit's, which draws nothing. The design
    // document says the same: "not a new wire type — a replacement renderer".
    private val replacementRenderers = setOf(io.github.youndie.kompot.UnknownComponent::class)

    @Test
    fun `the registry contains exactly the renderers this build claims`() {
        val actual = (konektRenderers.keys - replacementRenderers).map { it.wireName() }.toSet()

        assertEquals(rendered, actual, "the renderer map and this test's list of what is drawn disagree")
    }

    @Test
    fun `every name in the dictionary is accounted for, drawn or not`() {
        // The guard that makes the two lists above worth having: a tenth component added to the
        // dictionary belongs in one of them, and until somebody says which, this fails.
        assertEquals(
            konektWireNames.toSet(),
            rendered + notYetRendered,
            "a component in the dictionary is in neither list, so nobody has decided whether it draws",
        )
        assertTrue((rendered intersect notYetRendered).isEmpty(), "a component is in both lists")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a screen drawn through the registry shows the card and the toolkit's own nodes`() {
        // The registry's map is private, so there is nothing to inspect — and rendering is the better
        // question anyway. A registry holding ours alone would draw the card and nothing around it:
        // no column, no text, which on a real screen looks like one card floating in a blank.
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalKompotDesignSystem provides KonektDesignSystem(),
                        LocalKompotRegistry provides konektRegistry(),
                    ) {
                        ColumnRenderer().Render(
                            component =
                                ColumnComponent(
                                    id = "home",
                                    children =
                                        listOf(
                                            TextComponent(id = "balance", text = "\$38"),
                                            UsageCounterCardComponent(
                                                id = "counter-minutes",
                                                title = "Minutes",
                                                valueText = "100 min left",
                                                captionText = "Minutes run out in about two days at your current pace.",
                                                progress = 0.9f,
                                                state = CounterStates.LOW,
                                            ),
                                        ),
                                ),
                            actionHandler = KompotActionHandler { },
                            formController = FormController(FormSchema(formId = "home", fields = emptyList())),
                        )
                    }
                }
            }

            // The toolkit's node —
            onNodeWithText("\$38").assertExists()
            // — and ours, every string of it, arriving ready rather than assembled here.
            onNodeWithText("Minutes").assertExists()
            onNodeWithText("100 min left").assertExists()
            onNodeWithText("Minutes run out in about two days at your current pace.").assertExists()
        }
    }

    private fun kotlin.reflect.KClass<out KompotComponent>.wireName(): String =
        annotations.filterIsInstance<SerialName>().singleOrNull()?.value
            ?: error("$simpleName carries no @SerialName, so it has no wire type")
}
