package io.konekt.client.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.ColumnRenderer
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotDegradationKind
import io.github.youndie.kompot.KompotDegradationSink
import io.github.youndie.kompot.LocalKompotDegradationSink
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.client.net.konektClientJson
import io.konekt.client.theme.KonektDesignSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The frame the canvas draws and a naive client can never reach.
//
// It is unreachable by accident because a client that registers everything the server sends never
// meets an unknown type — which is Risk 5 in the research, and the reason this test builds the case
// from a WIRE PAYLOAD rather than by constructing an UnknownComponent: the decode is half the
// mechanism, and a test that skips it proves the drawing and not the degradation.
@OptIn(ExperimentalTestApi::class)
class UnknownBlockRendererTest {
    // THE TEST THAT WOULD HAVE CAUGHT IT, and the one the fixture above cannot be.
    //
    // Every other case here composes once and never again, so "reported once" was true of them
    // whatever the renderer did — the report sat in the composable body and fired on every
    // recomposition, and the count an operator reads was a function of how often Compose redrew. It
    // was found by a stand test on a slower machine, where a theme arriving mid-composition caused
    // one more pass and one more record.
    @Test
    fun `a redraw of the same component is not a second degradation`() {
        val sink = Recording()

        runComposeUiTest {
            // A state the test can change, so the recomposition is real rather than hoped for.
            var nudge by mutableStateOf(0)

            setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalKompotDesignSystem provides KonektDesignSystem(),
                        LocalKompotRegistry provides konektRegistry(),
                        LocalKompotDegradationSink provides sink,
                        LocalUnknownBlockDensity provides UnknownBlockDensity.LINE,
                    ) {
                        // Read so the composition actually depends on it.
                        @Suppress("UNUSED_EXPRESSION")
                        nudge
                        ColumnRenderer().Render(
                            component =
                                konektClientJson.decodeKompotComponent(
                                    screenWithSomethingNew,
                                ) as ColumnComponent,
                            actionHandler = KompotActionHandler { },
                            formController = FormController(FormSchema(formId = "u", fields = emptyList())),
                        )
                    }
                }
            }

            waitForIdle()
            assertEquals(1, sink.reports.size, "the first composition reported ${sink.reports.size} times")

            repeat(3) { nudge++ }
            waitForIdle()
        }

        assertEquals(
            1,
            sink.reports.size,
            "three redraws produced ${sink.reports.size} records — the count follows Compose, not the screen",
        )
    }

    private class Recording : KompotDegradationSink {
        val reports = mutableListOf<Triple<KompotDegradationKind, String, Boolean>>()

        override fun onUnknown(
            kind: KompotDegradationKind,
            originalType: String,
            drawnAsFallback: Boolean,
        ) {
            reports += Triple(kind, originalType, drawnAsFallback)
        }
    }

    // The canvas labels its example `esim_transfer_widget`, so that is what this sends.
    private val screenWithSomethingNew =
        """
        {"type":"column","id":"home","children":[
          {"type":"text","id":"balance","text":"${'$'}38"},
          {"type":"esim_transfer_widget","id":"transfer"},
          {"type":"text","id":"after","text":"Still here"}
        ]}
        """.trimIndent()

    private fun render(
        density: UnknownBlockDensity,
        payload: String = screenWithSomethingNew,
        sink: Recording = Recording(),
        assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ): Recording {
        val screen = konektClientJson.decodeKompotComponent(payload)

        runComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalKompotDesignSystem provides KonektDesignSystem(),
                        LocalKompotRegistry provides konektRegistry(),
                        LocalKompotDegradationSink provides sink,
                        LocalUnknownBlockDensity provides density,
                    ) {
                        ColumnRenderer().Render(
                            component = screen as ColumnComponent,
                            actionHandler = KompotActionHandler { },
                            formController = FormController(FormSchema(formId = "u", fields = emptyList())),
                        )
                    }
                }
            }
            assertions()
        }

        return sink
    }

    @Test
    fun `a type this build does not know draws a line, and its neighbours still render`() {
        val sink =
            render(UnknownBlockDensity.LINE) {
                onNodeWithText(UnknownBlockRenderer.LINE_TEXT).assertExists()
                // THE HALF THAT MATTERS MORE. A placeholder is worth having only if everything around
                // it survived — the whole argument for an additive dictionary is that a future
                // component costs a widget and never the screen.
                onNodeWithText("\$38").assertExists()
                onNodeWithText("Still here").assertExists()
            }

        assertEquals(1, sink.reports.size, "reported ${sink.reports.size} times for one unknown component")
        assertEquals(KompotDegradationKind.UNKNOWN_COMPONENT, sink.reports.single().first)
        assertEquals("esim_transfer_widget", sink.reports.single().second)
        assertEquals(false, sink.reports.single().third, "we drew a placeholder, not the thing itself")
    }

    @Test
    fun `the same type draws a card when it is the screen's subject`() {
        render(UnknownBlockDensity.CARD) {
            onNodeWithText(UnknownBlockRenderer.HEADLINE).assertExists()
            onNodeWithText(UnknownBlockRenderer.BODY).assertExists()
        }
    }

    @Test
    fun `a fallback the server named is drawn instead of our apology`() {
        val withFallback =
            """
            {"type":"column","id":"home","children":[
              {"type":"esim_transfer_widget","id":"t",
               "fallback":{"type":"text","id":"stand-in","text":"Transfers are on the web for now"}}
            ]}
            """.trimIndent()

        val sink =
            render(UnknownBlockDensity.CARD, payload = withFallback) {
                // The server chose to replace a component, so it knows what the replacement stands in
                // for. Drawing our placeholder over it would throw that away.
                onNodeWithText("Transfers are on the web for now").assertExists()
            }

        assertEquals(1, sink.reports.size)
        assertTrue(sink.reports.single().third, "a fallback was drawn and the report says it was not")
    }

    @Test
    fun `the screen name is not put in front of the subscriber`() {
        val sink = render(UnknownBlockDensity.CARD) { }

        // The wire name goes to the SINK, where an operator can count it. On the screen it is a word
        // a subscriber cannot act on, which is why the copy says what to do instead.
        assertTrue("esim_transfer_widget" !in UnknownBlockRenderer.HEADLINE)
        assertTrue("esim_transfer_widget" !in UnknownBlockRenderer.BODY)
        assertTrue("esim_transfer_widget" !in UnknownBlockRenderer.LINE_TEXT)
        assertEquals("esim_transfer_widget", sink.reports.single().second)
    }
}
