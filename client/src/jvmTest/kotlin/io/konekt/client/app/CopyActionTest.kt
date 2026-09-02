package io.konekt.client.app

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.feature.shell.shared.api.CopyAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A COPY LANDS ON THE CLIPBOARD AND GOES NO FURTHER (`B-115`): the text the action carries is what
// the clipboard gets, and the host — and through it the server — hears nothing. The action code is
// a credential, and a copy that reported back would put it in an access log.
@OptIn(ExperimentalTestApi::class)
class CopyActionTest {
    private val code = "LPA:1\$rsp.konekt.io\$8F214C90"
    private val button = ButtonComponent(id = "copy", text = "Copy activation code", action = CopyAction(code))

    @Suppress("DEPRECATION")
    private class Pasteboard : ClipboardManager {
        var held: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            held = annotatedString
        }

        override fun getText(): AnnotatedString? = held
    }

    private inner class Fake : ScreenSource {
        override suspend fun fetch(address: String): Screen = Screen.Tree(button)

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

        @Composable
        override fun render(
            screen: Screen,
            onAction: (KompotAction) -> Unit,
        ) {
            val tree = (screen as Screen.Tree).component as ButtonComponent
            Text(tree.text, modifier = Modifier.clickable { onAction(tree.action) })
        }
    }

    @Test
    fun `the text lands on the clipboard and the host hears nothing`() {
        val pasteboard = Pasteboard()
        val reached = mutableListOf<KompotAction>()

        runComposeUiTest {
            setContent {
                @Suppress("DEPRECATION")
                CompositionLocalProvider(LocalClipboardManager provides pasteboard) {
                    KonektApp(
                        screens = Fake(),
                        address = "/qr",
                        topic = "t",
                        darkMode = false,
                        theme = null,
                        onAction = { action ->
                            reached += action
                            null
                        },
                    )
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(button.text).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(button.text).performClick()
            waitForIdle()
        }

        assertEquals(code, pasteboard.held?.text, "the clipboard did not get the code")
        assertTrue(reached.isEmpty(), "a copy reached the host: $reached")
    }
}
