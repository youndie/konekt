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
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.components.BottomNavComponent
import io.konekt.components.BottomNavItem
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// WHAT IS ON SCREEN **DURING** A FETCH, which is the only place `B-111` lives.
//
// A tree assertion cannot see it: the tree is correct before the navigation and correct after, and the
// defect is entirely in between. So this harness holds the second response open and asks what is
// drawn while it is held.
//
// The defect it was written for: `remember(current) { mutableStateOf<Screen?>(null) }` reset the
// screen the instant the destination changed — before any request went out — and the bottom bar
// travels with the tree, so the whole frame disappeared for a round trip and came back together.
@OptIn(ExperimentalTestApi::class)
class NavigationKeepsTheScreenTest {
    private object GoSomewhereElse : KompotAction

    private fun card(text: String) =
        UsageCounterCardComponent(
            id = "counter",
            title = "Data",
            valueText = text,
            state = CounterStates.NORMAL,
            progress = 0.5f,
        )

    private fun tree(text: String) = Screen.Tree(card(text))

    // THE SHAPE A TAB SCREEN ACTUALLY HAS: a column whose last child is the bar. `withoutShell` is
    // what splits the two, and the frame draws the bar OUTSIDE the content box — so a fixture without
    // one cannot say anything about the thing that was reported, which was the bar disappearing.
    private fun shelled(text: String) =
        Screen.Tree(
            ColumnComponent(
                id = "root",
                children =
                    listOf(
                        card(text),
                        BottomNavComponent(
                            id = "shell-nav",
                            items = listOf(BottomNavItem(label = "Home", action = GoSomewhereElse, selected = true)),
                        ),
                    ),
            ),
        )

    // ONE ANSWER PER ADDRESS, and the second one is a gate this test opens by hand. `CompletableDeferred`
    // rather than a delay: a test that waits a fixed time asserts about a race it does not control, and
    // this one has to be certain the fetch is still in flight while it looks.
    private inner class Gated(
        private val second: CompletableDeferred<Screen>?,
    ) : ScreenSource {
        override suspend fun fetch(address: String): Screen =
            if (address == "/first") tree("the first screen") else second!!.await()

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

        // The bar, drawn as its labels. Enough for an assertion about whether it is on screen, which
        // is the whole subject.
        @Composable
        override fun renderNode(
            component: KompotComponent,
            onAction: (KompotAction) -> Unit,
        ) {
            (component as? BottomNavComponent)?.items?.forEach { item ->
                Text(item.label, modifier = Modifier.clickable { onAction(item.action) })
            }
        }

        // A COLUMN OR A CARD, because `withoutShell` hands back the tree with the bar removed and
        // that is still a column when the screen had one. A fixture that only understood the card
        // rendered the tab screens fine and threw on the shelled ones.
        @Composable
        override fun render(
            screen: Screen,
            onAction: (KompotAction) -> Unit,
        ) {
            Draw((screen as Screen.Tree).component, onAction)
        }

        @Composable
        private fun Draw(
            node: KompotComponent,
            onAction: (KompotAction) -> Unit,
        ) {
            when (node) {
                is UsageCounterCardComponent -> {
                    Text(node.valueText, modifier = Modifier.clickable { onAction(GoSomewhereElse) })
                }

                is ColumnComponent -> {
                    node.children.forEach { Draw(it, onAction) }
                }

                else -> {}
            }
        }
    }

    @Test
    fun `the screen stays on the screen while the next one is being fetched`() =
        runComposeUiTest {
            val second = CompletableDeferred<Screen>()
            setContent {
                KonektApp(
                    screens = Gated(second),
                    address = "/first",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = { Destination.next("/second") },
                )
            }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("the first screen").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("the first screen").performClick()
            waitForIdle()

            // THE ASSERTION, and it is about the moment the second answer has NOT arrived.
            assertTrue(
                onAllNodesWithText("the first screen").fetchSemanticsNodes().isNotEmpty(),
                "the window went blank while the next screen was being fetched, which is `B-111`",
            )

            second.complete(tree("the second screen"))
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("the second screen").fetchSemanticsNodes().isNotEmpty()
            }

            // And it does not linger: a frame that kept the old tree AFTER the new one arrived would
            // satisfy the assertion above and be a worse defect than the one it replaced.
            assertFalse(
                onAllNodesWithText("the first screen").fetchSemanticsNodes().isNotEmpty(),
                "the previous screen is still drawn after its replacement arrived",
            )
        }

    // AND THE BAR STAYS, which is what was actually reported: not "the content flickers" but "the
    // application disappears". The bar travels inside the tree (`B-51`), so when the tree went so did
    // it — along with the title and the frame — and everything came back at once.
    @Test
    fun `a tab press keeps the bar on screen while the next tab loads`() =
        runComposeUiTest {
            val second = CompletableDeferred<Screen>()
            val source =
                object : ScreenSource by Gated(second) {
                    override suspend fun fetch(address: String): Screen =
                        if (address == "/first") shelled("the first screen") else second.await()
                }

            setContent {
                KonektApp(
                    screens = source,
                    address = "/first",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = { Destination.next("/second") },
                )
            }

            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty() }

            // Pressed on the BAR, which is how a tab is switched, rather than on the content.
            onNodeWithText("Home").performClick()
            waitForIdle()

            assertTrue(
                onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty(),
                "the bar disappeared while the next tab was being fetched — `B-111` as reported",
            )

            second.complete(shelled("the second screen"))
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("the second screen").fetchSemanticsNodes().isNotEmpty()
            }
        }

    // THE STALE TREE MUST NOT TAKE PRESSES, which is the half that makes keeping it defensible at
    // all. A screen that looks live under the address of a different one, and still starts errands,
    // is worse than the blank window.
    @Test
    fun `the screen being replaced stops taking presses`() =
        runComposeUiTest {
            val second = CompletableDeferred<Screen>()
            var presses = 0
            setContent {
                KonektApp(
                    screens = Gated(second),
                    address = "/first",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = {
                        presses++
                        Destination.next("/second")
                    },
                )
            }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("the first screen").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("the first screen").performClick()
            waitForIdle()

            // A second press, on the tree that is on its way out.
            onNodeWithText("the first screen").performClick()
            waitForIdle()

            assertTrue(presses == 1, "the screen being replaced fired $presses actions; it must fire one")

            second.complete(tree("the second screen"))
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("the second screen").fetchSemanticsNodes().isNotEmpty()
            }

            // AND IT TAKES THEM AGAIN once it is the current screen. A barrier that outlived the
            // fetch would be a frame that goes permanently deaf, which no assertion above can see.
            onNodeWithText("the second screen").performClick()
            waitForIdle()
            assertTrue(presses == 2, "the screen stopped taking presses after it had finished loading")
        }

    // A FETCH THAT FAILED IS NOT A FETCH THAT IS SLOW, and before `B-111` they were the same blank
    // window: `screens.fetch` threw out of a `LaunchedEffect` with nothing catching it, the coroutine
    // died, and the composition sat empty for ever with no way to ask again.
    @Test
    fun `a fetch that throws says so and can be retried`() =
        runComposeUiTest {
            var attempts = 0
            val source =
                object : ScreenSource by Gated(null) {
                    override suspend fun fetch(address: String): Screen {
                        attempts++
                        if (attempts == 1) throw IllegalStateException("the network is down")
                        return tree("the screen at last")
                    }
                }

            setContent {
                KonektApp(
                    screens = source,
                    address = "/first",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = { null },
                )
            }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Try again").performClick()

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("the screen at last").fetchSemanticsNodes().isNotEmpty()
            }
        }
}
