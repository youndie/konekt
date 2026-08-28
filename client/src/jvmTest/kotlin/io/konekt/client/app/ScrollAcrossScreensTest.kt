package io.konekt.client.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
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

// WHERE A SCREEN OPENS, which is a question the holder answers and nothing asked.
//
// The scroll state was remembered at the call site with no key, so it survived every kind of change
// underneath it. Pressing a step of the install wizard replaced the content while the position stayed
// half a screen down, and the banner a subscriber had been waiting for since they paid — "Your eSIM
// is ready." — arrived above the fold (`B-75`).
//
// The address does not change between wizard steps, so "reset on a new address" would not have fixed
// it. What separates the cases is WHY the tree was fetched: a press means a new screenful and belongs
// at the top; a live update or the refetch after a stream gap means the same screen, newer, and must
// leave a reader where they were. Both halves are asserted here, because a holder that reset on
// everything would jump somebody to the top each time a counter ticked.
@OptIn(ExperimentalTestApi::class)
class ScrollAcrossScreensTest {
    private object Press : KompotAction

    // Tall enough that the last row is off the screen and the first leaves it when scrolled — the
    // whole subject is a position, so the fixture has to have somewhere to be.
    private val rows = (1..40).map { "row $it" }

    private inner class Fake : ScreenSource {
        // Counted so a test can wait for the refetch it triggered rather than for a duration.
        @Volatile
        var fetches: Int = 0

        // Buffered, so a test can emit into it without a collector racing it.
        val restarts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        // EVERY ANSWER DIFFERS FROM THE LAST, which is not decoration: a refetch after a stream gap
        // exists to bring back CHANGED data, and a fixture that answers an identical tree makes any
        // "reset when the content changes" implementation look correct. It did — the first version of
        // this fake returned the same component every time, and the mutation that resets on every
        // fetch passed both tests.
        //
        // The title is left alone because the assertions read it; what varies is the progress, and
        // `render` draws it so the difference is in the tree rather than only in the data class.
        override suspend fun fetch(address: String): Screen {
            fetches += 1
            return Screen.Tree(
                UsageCounterCardComponent(
                    id = "counter",
                    title = address,
                    valueText = "press me",
                    state = CounterStates.NORMAL,
                    progress = fetches / 100f,
                ),
            )
        }

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

        override val streamRestarted = restarts.asSharedFlow()

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
            val card = (screen as Screen.Tree).component as UsageCounterCardComponent
            Column {
                Text("top of ${card.title}")
                rows.forEach { Text(it, modifier = Modifier.height(40.dp)) }
                Text("fetch ${card.progress}")
                Text("press me", modifier = Modifier.clickable { onAction(Press) })
            }
        }
    }

    // The subject: a press answered with the address we are already on — a wizard step, a confirmed
    // purchase — replaces the content, and the reader is put back at the top of it.
    //
    // ASSERTED ON WHAT IS DISPLAYED, not on what exists. A `Column` with `verticalScroll` composes
    // every child whatever its position, so `onAllNodesWithText` finds a row that scrolled off the
    // screen half an hour ago — the first version of this test asserted presence and failed on its
    // own precondition.
    @Test
    fun `a press that answers the same address opens the new content at the top`() {
        val source = Fake()

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = source,
                    address = "/only",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    // The same address, which is exactly the wizard's shape.
                    onAction = { Destination.next("/only") },
                )
            }

            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("row 1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("press me").performScrollTo()
            waitForIdle()
            // The precondition, so what follows is about a scroll that was UNDONE rather than one
            // that never happened.
            onNodeWithText("top of /only").assertIsNotDisplayed()

            val before = source.fetches
            onNodeWithText("press me").performClick()
            waitUntil(timeoutMillis = 5_000) { source.fetches > before }
            waitForIdle()

            onNodeWithText("top of /only").assertIsDisplayed()
        }
    }

    // THE OTHER HALF, and without it the fix above is "reset on everything", which would throw a
    // reader to the top every time a counter ticked or a stream reconnected.
    @Test
    fun `a refetch nobody asked for leaves the reader where they were`() {
        val source = Fake()

        runComposeUiTest {
            setContent {
                KonektApp(
                    screens = source,
                    address = "/only",
                    topic = "t",
                    darkMode = false,
                    theme = null,
                    onAction = { null },
                )
            }

            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("row 1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("press me").performScrollTo()
            waitForIdle()
            onNodeWithText("top of /only").assertIsNotDisplayed()

            // A STREAM GAP. The holder clears its overlay and refetches the same screen — see `B-43`
            // for why, and why in that order. Nothing about it is a new screenful.
            val before = source.fetches
            assertTrue(source.restarts.tryEmit(Unit), "the restart was not delivered, so nothing was tested")
            waitUntil(timeoutMillis = 5_000) { source.fetches > before }
            waitForIdle()

            onNodeWithText("top of /only").assertIsNotDisplayed()
        }
    }
}
