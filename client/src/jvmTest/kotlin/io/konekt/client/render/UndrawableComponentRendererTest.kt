package io.konekt.client.render

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotDegradationKind
import io.github.youndie.kompot.KompotDegradationSink
import io.github.youndie.kompot.LocalKompotDegradationSink
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.konekt.client.app.KonektDegradation
import io.konekt.client.app.KonektDegradationSink
import io.konekt.client.theme.KonektTheme
import io.konekt.components.StepMeterComponent
import kotlin.test.Test
import kotlin.test.assertEquals

// THE MECHANISM THAT NOW HAS NO PRODUCTION INSTANCE, and saying so is the point.
//
// `UndrawableComponentRenderer` exists because a type in konekt's dictionary with no entry in the
// registry reached no block, no sink and no record — the toolkit's red fallback drew it and nothing
// counted it. `banner` was in that state and the home screen sent one.
//
// `B-45` gave every one of the nine types a renderer, so **no served screen can produce this case any
// more**. That is the right outcome and it puts this test in the category B-44 itself criticised: a
// fixture supplying its own condition. The difference is what it is FOR — not to prove a branch a
// screen reaches, but to keep the branch working for the day a tenth type is added to the dictionary
// and somebody forgets to register it. `KonektRendererCoverageTest.every dictionary type is
// registered` is what fails on that day; this is what makes the failure a block rather than red text.
@OptIn(ExperimentalTestApi::class)
class UndrawableComponentRendererTest {
    private class Recording : KompotDegradationSink {
        val records = mutableListOf<KonektDegradation>()
        private val delegate = KonektDegradationSink { records += it }

        override fun onUnknown(
            kind: KompotDegradationKind,
            originalType: String,
            drawnAsFallback: Boolean,
        ) = delegate.onUnknown(kind, originalType, drawnAsFallback)

        fun konekt() = delegate
    }

    private val meter = StepMeterComponent(id = "m", current = 1, total = 3)

    @Test
    fun `it draws the block and records the type with the undrawable cause`() {
        val records = mutableListOf<KonektDegradation>()
        val sink = KonektDegradationSink { records += it }

        runComposeUiTest {
            setContent {
                KonektTheme(theme = null, darkMode = false) {
                    CompositionLocalProvider(LocalKompotDegradationSink provides sink) {
                        UndrawableComponentRenderer<StepMeterComponent>("step_meter").Render(
                            component = meter,
                            actionHandler = KompotActionHandler { },
                            formController = FormController(FormSchema(formId = "t", fields = emptyList())),
                        )
                    }
                }
            }
            waitForIdle()

            // The same block a type nobody has heard of draws. A subscriber cannot tell the two apart
            // and should not have to: "update to see it" is the only move either one leaves them.
            onNodeWithText(UnknownBlockRenderer.LINE_TEXT).assertExists()
        }

        // ONE RECORD, not two. It reports the cause itself and then delegates the DRAWING to
        // `UnknownBlockRenderer` — which reports too, unless told not to. Left reporting, one
        // component produces two records saying different things about the same failure.
        assertEquals(1, records.size, "expected exactly one record: $records")
        assertEquals("step_meter", records.single().originalType)
        assertEquals(KonektDegradation.Cause.UNDRAWABLE, records.single().cause)
    }

    @Test
    fun `a sink that is not konekt's still hears about it, without the cause`() {
        // A deployment binding some other `KompotDegradationSink` cannot be told which of the two
        // failures happened — kompot's interface has nowhere to carry it. It must still hear about
        // the component, because silence is what this whole item exists to end.
        val heard = mutableListOf<String>()
        val foreign =
            object : KompotDegradationSink {
                override fun onUnknown(
                    kind: KompotDegradationKind,
                    originalType: String,
                    drawnAsFallback: Boolean,
                ) {
                    heard += originalType
                }
            }

        runComposeUiTest {
            setContent {
                KonektTheme(theme = null, darkMode = false) {
                    CompositionLocalProvider(LocalKompotDegradationSink provides foreign) {
                        UndrawableComponentRenderer<StepMeterComponent>("step_meter").Render(
                            component = meter,
                            actionHandler = KompotActionHandler { },
                            formController = FormController(FormSchema(formId = "t", fields = emptyList())),
                        )
                    }
                }
            }
            waitForIdle()
        }

        assertEquals(listOf("step_meter"), heard)
    }
}
