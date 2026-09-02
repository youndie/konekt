package io.konekt.client.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.components.ScreenHeaderComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE HEADER'S CIRCLE PRESSES THE HEADER'S ACTION (`B-115`), and it is the only back control on the
// screen. The wizard used to draw a `Back` pill under the shell's chevron: the pill went a step
// back, the chevron left the flow, and nothing on screen said which was which. This drives
// `KonektApp` with a tree that carries a `screen_header` and watches what reaches the host.
@OptIn(ExperimentalTestApi::class)
class ScreenHeaderIsTheBackControlTest {
    private object StepBack : KompotAction

    private fun tree(header: ScreenHeaderComponent?) =
        ColumnComponent(
            id = "wizard",
            children = listOfNotNull(header, TextComponent(id = "body", text = "the step")),
        )

    private inner class Fake(
        private val screen: KompotComponent,
    ) : ScreenSource {
        override suspend fun fetch(address: String): Screen = Screen.Tree(screen)

        override suspend fun navigation(): Map<String, String>? = null

        override suspend fun brandTheme(): KompotTheme? = null

        override suspend fun fetchForm(address: String): KompotFormResponse =
            throw UnsupportedOperationException("this fixture serves screens, not forms")

        override fun patchFetcher(
            address: String,
            formId: String,
        ): PatchFetcher = throw UnsupportedOperationException("this fixture serves screens, not forms")

        override fun pages(): KompotPageLoader =
            object : KompotPageLoader {
                override suspend fun loadPage(
                    url: String,
                    params: Map<String, String>,
                ): KompotPageResponse = error("this fixture serves no pages")
            }

        override fun updates(topic: String) = MutableSharedFlow<ComponentUpdate>().asSharedFlow()

        override val streamRestarted = MutableSharedFlow<Unit>().asSharedFlow()

        @Composable
        override fun renderNode(
            component: KompotComponent,
            onAction: (KompotAction) -> Unit,
        ) = Unit

        // The content the shell hands over, drawn as its texts: what this test looks at is what the
        // SHELL drew above it, so the content only needs to be findable.
        @Composable
        override fun render(
            screen: Screen,
            onAction: (KompotAction) -> Unit,
        ) {
            val root = (screen as Screen.Tree).component
            (root as? ColumnComponent)?.children?.filterIsInstance<TextComponent>()?.forEach { Text(it.text) }
            (root as? TextComponent)?.let { Text(it.text) }
        }
    }

    private fun pressesReaching(header: ScreenHeaderComponent?): List<KompotAction> {
        val reached = mutableListOf<KompotAction>()
        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = Fake(tree(header)),
                    address = "/wizard",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = { action ->
                        reached += action
                        null
                    },
                )
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("the step").fetchSemanticsNodes().isNotEmpty() }
            header?.let { onNodeWithText(it.title).assertExists() }
            val word = if (header?.closes == true) "Close" else "Back"
            onNodeWithContentDescription(word).performClick()
            waitForIdle()
        }
        return reached
    }

    @Test
    fun `the circle presses the header's action, and it is the only back control`() {
        val header = ScreenHeaderComponent(id = "h", title = "Install eSIM", action = StepBack)

        val reached = pressesReaching(header)

        assertEquals(listOf<KompotAction>(StepBack), reached, "the header's action did not reach the host")
    }

    @Test
    fun `a closing header without an action is drawn as a cross and asks nothing of the host`() {
        val header = ScreenHeaderComponent(id = "h", title = "Install eSIM", closes = true)

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = Fake(tree(header)),
                    address = "/wizard",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = { null },
                )
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("the step").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodesWithContentDescription("Close").fetchSemanticsNodes().size == 1, "no cross, or two")
            assertTrue(
                onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty(),
                "a chevron beside the cross",
            )
        }
    }
}
