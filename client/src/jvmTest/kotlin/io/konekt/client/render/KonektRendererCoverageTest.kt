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

// Which of konekt's eleven types this client can draw, stated rather than implied.
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
    // `banner` joined these after an iOS build drew a red "Unknown component" where the home screen's
    // "no plan is active" message should have been. It was in `notYetRendered` below, which recorded
    // the fact and treated it as a decision — and the decision was invisible, because a type that
    // DECODES and has no renderer is not an `UnknownComponent` and never reaches konekt's degradation
    // block. Every test missed it by topping up first, so the banner was never sent.
    // ALL ELEVEN, and the second list below is still empty. `banner` joined after an iOS build drew a
    // red "Unknown component" where the home screen's "no plan is active" message should have been;
    // six joined with `B-45`, which is the screens they were waiting for; and `bottom_nav` joined
    // with `B-49` on the day it was added — which is what the emptiness of the second list is FOR.
    // It is the first component in this build that could not be added without also being drawn.
    private val rendered =
        setOf(
            "usage_counter_card",
            "esim_qr",
            "banner",
            "plan_card",
            "esim_card",
            "order_row",
            "snackbar",
            "step_meter",
            "skeleton",
            "bottom_nav",
            // `surface` joined the same way `bottom_nav` did, and had to: a container the server
            // sends and the client cannot draw is not a degraded card, it is a screen with its
            // contents missing — the children would go nowhere at all.
            "surface",
        )

    // EMPTY, AND KEPT. The list is what says "somebody decided this one does not draw yet" as opposed
    // to "nobody noticed" — and its emptiness is an assertion rather than a gap: a component added to
    // the dictionary lands in one of the two lists or fails the test below. It has now been paid for
    // once: `bottom_nav` arrived with `B-49` and this test is what refused the build until it drew.
    private val notYetRendered = emptySet<String>()

    // The toolkit's placeholder, and NOT a tenth entry in konekt's dictionary. `UnknownComponent` has
    // no @SerialName because it is never sent — it is what a decode produces for a type nobody knows —
    // and konekt registers a renderer for it to replace the toolkit's, which draws nothing. The design
    // document says the same: "not a new wire type — a replacement renderer".
    //
    // The two CONTAINERS are here for the same reason and it is not the same mechanism: `column` and
    // `row` are the toolkit's own wire types with the toolkit's own renderers, and konekt replaces
    // them only to provide `LocalUnknownBlockDensity` before delegating. They draw nothing of their
    // own, so counting them as konekt components would say this build renders two types it does not.
    private val replacementRenderers =
        setOf(
            io.github.youndie.kompot.UnknownComponent::class,
            io.github.youndie.kompot.standard.ColumnComponent::class,
            io.github.youndie.kompot.standard.RowComponent::class,
        )

    @Test
    fun `the registry contains exactly the renderers this build claims`() {
        val actual = (konektRenderers.keys - replacementRenderers).map { it.wireName() }.toSet()

        assertEquals(
            rendered + notYetRendered,
            actual,
            "the renderer map and this test's lists disagree",
        )
    }

    @Test
    fun `every dictionary type is registered, drawn properly or drawn as a block`() {
        // THE GUARD THAT DID NOT EXIST, and its absence is what let `banner` reach a screen with no
        // renderer at all. A type in the dictionary and not in the registry does not degrade — it
        // falls through to the toolkit's red text, which no sink counts.
        //
        // "Registered" is the claim, not "drawn": six of these draw the degradation block deliberately
        // (`UndrawableComponentRenderer`), which is a visible, counted, honest gap rather than a silent
        // one.
        val registered = (konektRenderers.keys - replacementRenderers).map { it.wireName() }.toSet()

        assertEquals(
            emptySet(),
            konektWireNames.toSet() - registered,
            "a dictionary type has no renderer at all, so a screen carrying it draws the toolkit's " +
                "red fallback and nothing counts it",
        )
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
