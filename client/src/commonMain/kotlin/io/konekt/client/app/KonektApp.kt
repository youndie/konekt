package io.konekt.client.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotRealtimeUpdates
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.client.theme.KonektTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// THE APPLICATION, which this repository had every part of and none of.
//
// A screen holder rather than a navigation graph: it fetches ONE component tree by address, renders
// it through the registry, and provides the theme and the update overlay around it. `kompot-navigation`
// exists and this build does not use it (research §1.11), so which screen to show is a value handed in
// rather than a framework decision.
//
// What it owns that nothing else can, and both were found by reading the toolkit in B-18:
//
//   * THE OVERLAY IS CLEARED ON A STREAM RESTART. `KompotRealtimeProvider` keys its map by topic and
//     konekt serves one topic per subscriber, so the toolkit's own eraser never fires here — an update
//     recorded before a gap keeps shadowing the correct component of a screen fetched after it, for
//     the life of the composition, with a healthy network and no error anywhere. That is the only
//     unbounded failure B-18 found, and this is the only place it can be ended.
//   * THE SCREEN IS REFETCHED AFTER THE GAP, in that order. Clearing without refetching leaves the
//     tree as it was before the gap; refetching without clearing leaves the stale overlay on top of a
//     correct tree. Neither half is worth anything alone.
@Composable
fun KonektApp(
    screens: ScreenSource,
    address: String,
    topic: String,
    darkMode: Boolean,
    theme: KompotTheme? = null,
) {
    var tree by remember(address) { mutableStateOf<KompotComponent?>(null) }

    // OUR OWN MAP, not `KompotRealtimeProvider`'s. That composable holds its `SnapshotStateMap`
    // privately and empties it only when the topic changes, which konekt never does — so the map has
    // to be ours for the clear below to be possible at all. Reported upstream rather than forked.
    val updates = remember(topic) { mutableStateMapOf<String, KompotComponent>() }

    LaunchedEffect(address) { tree = screens.fetch(address) }

    LaunchedEffect(topic) {
        launch {
            screens.updates(topic).collect { (componentId, component) ->
                updates[componentId] = component
            }
        }

        screens.streamRestarted.collect {
            // CLEAR, THEN REFETCH. The order is the correctness: between the two the screen shows the
            // pre-gap tree with no overlay, which is stale and honest; the other way round it shows a
            // fresh tree wearing a stale overlay, which is wrong and looks fine.
            updates.clear()
            tree = screens.fetch(address)
        }
    }

    KonektTheme(theme = theme, darkMode = darkMode) {
        CompositionLocalProvider(LocalKompotRealtimeUpdates provides updates) {
            tree?.let { screens.render(it) }
        }
    }
}

// WHAT THE HOLDER NEEDS, AS AN INTERFACE, so that a test can drive the sequence this application
// exists to get right — a stale overlay, a gap, a refetch — without a server, a socket or a stand.
//
// It is not a repository abstraction hiding HTTP. Every method here is something the holder does to
// the outside world, and the real implementation is a handful of lines over the client this module
// already builds.
interface ScreenSource {
    suspend fun fetch(address: String): KompotComponent

    fun updates(topic: String): Flow<ComponentUpdate>

    val streamRestarted: Flow<Unit>

    @Composable
    fun render(tree: KompotComponent)
}

data class ComponentUpdate(
    val componentId: String,
    val component: KompotComponent,
)
