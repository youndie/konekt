package io.konekt.client.app

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.test.Test
import kotlin.test.assertTrue

// WHERE BACK MAY POINT, and the two answers this holder used to get wrong in the same way.
//
// The bug was one line — `stack.pop().push(destination)` — and it was invisible because `pop()` on a
// stack of one returns the stack UNCHANGED: the toolkit refuses to leave it empty, correctly, so the
// login screen survived the pop and the home screen was pushed on top of it. A back control appeared
// on a tab, pointing at a code already spent. The mirror is worse and nobody had reached it: signing
// out put the just-abandoned screen behind the login screen, one press from a 401.
//
// Neither is visible in a golden — the frame is drawn by `KonektApp` and the screenshots photograph
// the screen tree — so this is the harness that can see it.
@OptIn(ExperimentalTestApi::class)
class BackControlTest {
    private object Press : KompotAction

    private val screen =
        UsageCounterCardComponent(
            id = "counter",
            title = "Data",
            valueText = "press me",
            state = CounterStates.NORMAL,
            progress = 0.5f,
        )

    // Renders one clickable label and fires `Press`, which is all a stack assertion needs: the
    // subject is what the holder does with the ANSWER, not what drew the button.
    private inner class Fake : ScreenSource {
        override suspend fun fetch(address: String): Screen = Screen.Tree(screen)

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
            val tree = (screen as Screen.Tree).component as UsageCounterCardComponent
            Text(tree.valueText, modifier = Modifier.clickable { onAction(Press) })
        }
    }

    private fun drawsBackAfterThePress(answer: Destination): Boolean {
        var back = false
        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = Fake(),
                    address = "/first",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = { answer },
                )
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("press me").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("press me").performClick()
            waitForIdle()
            back = onAllNodesWithText("← Back").fetchSemanticsNodes().isNotEmpty()
        }
        return back
    }

    @Test
    fun `a boundary leaves nothing to go back to`() {
        assertTrue(
            !drawsBackAfterThePress(Destination.startOver("/second")),
            "signing in put a back control on the home screen, pointing at a login already used",
        )
    }

    // THE POSITIVE CONTROL, and without it the assertion above passes on a holder that draws no back
    // control at all — which is the shape of vacuous check this repository has been bitten by before.
    @Test
    fun `an ordinary step still has one`() {
        assertTrue(
            drawsBackAfterThePress(Destination.next("/second")),
            "no back control after an ordinary move — the check above proves nothing",
        )
    }
}
