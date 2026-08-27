package io.konekt.client.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotRealtimeUpdates
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals

// THE SEQUENCE THIS APPLICATION EXISTS TO GET RIGHT, driven end to end without a socket.
//
// B-18 found it by reading the toolkit and could not fix it: an update recorded before a stream gap
// keeps shadowing the correct component of a screen fetched AFTER the gap, for the life of the
// composition, with a healthy network, a fresh fetch and no error anywhere. It was the only unbounded
// failure among its findings, and it needed a screen holder that did not exist.
//
// The fixture is a fake `ScreenSource` rather than a stand, because what is being checked is an
// ORDER of operations inside the holder. A stand would exercise the same order through three
// processes and tell you less about which of them got it wrong.
@OptIn(ExperimentalTestApi::class)
class KonektAppTest {
    private val topic = "subscriber:sub-1"

    private fun card(
        id: String,
        text: String,
    ) = UsageCounterCardComponent(
        id = id,
        title = "Data",
        valueText = text,
        state = CounterStates.NORMAL,
        progress = 0.5f,
    )

    private class Fake(
        var current: KompotComponent,
    ) : ScreenSource {
        val restarts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val frames = MutableSharedFlow<ComponentUpdate>(extraBufferCapacity = 8)
        var fetches = 0

        // No kit: this fixture is about the ORDER of clear-then-refetch, and a theme fetch would
        // add a second suspending call to reason about for nothing.
        override suspend fun brandTheme(): io.github.youndie.kompot.theme.KompotTheme? = null

        // The shell draws the bar through this, and no fixture here serves one — so it draws
        // nothing rather than failing: an empty shell is a legitimate screen, unlike an empty page.
        @androidx.compose.runtime.Composable
        override fun renderNode(
            component: io.github.youndie.kompot.KompotComponent,
            onAction: (io.github.youndie.kompot.KompotAction) -> Unit,
        ) = Unit

        // Nothing here paginates. It fails rather than answering an empty page, so a fixture that
        // grew a paginated screen says so instead of quietly drawing a shorter list.
        override fun pages(): io.github.youndie.kompot.standard.KompotPageLoader =
            object : io.github.youndie.kompot.standard.KompotPageLoader {
                override suspend fun loadPage(
                    url: String,
                    params: Map<String, String>,
                ): io.github.youndie.kompot.standard.KompotPageResponse = error("this fixture serves no pages")
            }

        override suspend fun fetch(address: String): Screen {
            fetches++
            return Screen.Tree(current)
        }

        // No form here either, and refusing is better than answering an empty one: this fixture drives
        // the clear-then-refetch order, and a fake that quietly returned a blank form would let a
        // future test ask it for one and believe the answer.
        override suspend fun fetchForm(address: String): KompotFormResponse =
            throw UnsupportedOperationException("this fixture serves screens, not forms")

        override fun patchFetcher(
            address: String,
            formId: String,
        ): PatchFetcher = throw UnsupportedOperationException("this fixture serves screens, not forms")

        override fun updates(topic: String) = frames.asSharedFlow()

        override val streamRestarted = restarts.asSharedFlow()

        // The overlay is consulted here, exactly as `KompotRegistry.RenderNode` does it: the map wins
        // over the node, and the renderer is chosen from the REPLACEMENT. Reproduced rather than
        // called so the assertion is about the holder's map and not about the toolkit's registry.
        @Composable
        override fun render(
            screen: Screen,
            onAction: (io.github.youndie.kompot.KompotAction) -> Unit,
        ) {
            // This fixture serves trees only; a form would need a controller and this test is about
            // the clear-then-refetch order.
            val tree = (screen as Screen.Tree).component
            val effective = LocalKompotRealtimeUpdates.current[(tree as UsageCounterCardComponent).id] ?: tree
            Text((effective as UsageCounterCardComponent).valueText)
        }
    }

    @Test
    fun `an update recorded before a gap does not shadow the tree fetched after it`() =
        runComposeUiTest {
            val source = Fake(card("counter-data", "9.7 GB left"))

            setContent {
                KonektApp(screens = source, address = "/api/v1/screens/home", topic = topic, darkMode = false)
            }
            waitForIdle()

            // A live update lands and the screen follows it. This half already worked.
            source.frames.tryEmit(ComponentUpdate("counter-data", card("counter-data", "4.1 GB left")))
            waitForIdle()
            onNodeWithText("4.1 GB left").assertIsDisplayed()

            // THE GAP. While the stream was down the server moved the counter on, and the frame that
            // said so was broadcast into a topic nobody was collecting — by design, since
            // `Last-Event-ID` is deliberately unused and the client announces the gap instead.
            source.current = card("counter-data", "0.4 GB left")
            source.restarts.tryEmit(Unit)
            waitForIdle()

            // The refetched value, not the pre-gap overlay. Without the clear this reads "4.1 GB
            // left" forever: a screen permanently wrong about one component, with nothing anywhere
            // reporting a fault.
            onNodeWithText("0.4 GB left").assertIsDisplayed()
            assertEquals(2, source.fetches, "a stream restart must refetch, not only clear")
        }

    @Test
    fun `the first fetch draws what the server sent`() =
        runComposeUiTest {
            val source = Fake(card("counter-data", "9.7 GB left"))

            setContent {
                KonektApp(screens = source, address = "/api/v1/screens/home", topic = topic, darkMode = false)
            }
            waitForIdle()

            // The positive control. Without it "the refetched value is shown" is satisfied by a holder
            // that draws nothing at all until a restart.
            onNodeWithText("9.7 GB left").assertIsDisplayed()
            assertEquals(1, source.fetches)
        }
}
