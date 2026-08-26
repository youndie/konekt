package io.konekt.client.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotDegradationSink
import io.github.youndie.kompot.LocalKompotRealtimeUpdates
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.NavigateAction
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
// The default output, named rather than written inline as `{ }`. An empty lambda at a call site reads
// as "nothing to do here"; a constant with this name reads as what it is.
object KonektApp {
    val RECORDS_NOTHING: (KonektDegradation) -> Unit = { }

    // Named for the same reason: a deployment that handles no action should be one that chose to.
    val HANDLES_NOTHING: (KompotAction) -> Unit = { }
}

@Composable
fun KonektApp(
    screens: ScreenSource,
    address: String,
    topic: String,
    darkMode: Boolean,
    theme: KompotTheme? = null,
    // NOT DEFAULTED TO A NO-OP THAT LOOKS LIKE A SINK. A caller that does not want to record says so
    // by passing this explicitly; the default records nothing and is named for what it is, so a
    // deployment reporting nothing is a deployment that chose to rather than one that forgot.
    onDegradation: (KonektDegradation) -> Unit = KonektApp.RECORDS_NOTHING,
    // WHERE A `navigate` GOES, as a map from deeplink to address. Empty means this holder shows one
    // screen and refuses to move, which is what it did before `B-45` and is still the right answer for
    // a fixture.
    //
    // A MAP AND NOT `kompot-navigation`: research §1.11 records why this build does not use the
    // toolkit's graph, and the shape is unchanged — one screen at a time, addressed. What changes is
    // that the address is a value the holder can be handed AGAIN, which is the whole of navigation
    // until there is a back stack.
    routes: Map<String, String> = emptyMap(),
    // Everything that is not a transition. A handler that silently did nothing would make a button
    // with no handler indistinguishable from one whose handler is missing.
    onAction: (KompotAction) -> Unit = KonektApp.HANDLES_NOTHING,
) {
    // THE ADDRESS IS STATE NOW, seeded from the parameter. `remember(address)` on the seed rather
    // than `remember { }`: a caller that changes the address it passes still moves the holder, which
    // is what every existing test does.
    var current by remember(address) { mutableStateOf(address) }
    var tree by remember(current) { mutableStateOf<KompotComponent?>(null) }

    // OUR OWN MAP, not `KompotRealtimeProvider`'s. That composable holds its `SnapshotStateMap`
    // privately and empties it only when the topic changes, which konekt never does — so the map has
    // to be ours for the clear below to be possible at all. Reported upstream rather than forked.
    val updates = remember(topic) { mutableStateMapOf<String, KompotComponent>() }

    // PROVIDED AROUND THE WHOLE TREE, because the renderer reads it from a composition local and a
    // sink provided inside one screen would miss every other. `remember` on the callback so a
    // recomposition does not build a new sink and, with it, a new "reported once" state.
    val sink = remember(onDegradation) { KonektDegradationSink(onDegradation) }

    // FETCHED WHEN NOT SUPPLIED. A caller that hands one in — a screenshot fixture, a test — keeps
    // control; everything else gets the deployment's brand without having to ask for it.
    var fetchedTheme by remember { mutableStateOf<KompotTheme?>(null) }
    LaunchedEffect(theme) { if (theme == null) fetchedTheme = screens.brandTheme() }

    LaunchedEffect(current) { tree = screens.fetch(current) }

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
            tree = screens.fetch(current)
        }
    }

    KonektTheme(theme = theme ?: fetchedTheme, darkMode = darkMode) {
        CompositionLocalProvider(
            LocalKompotRealtimeUpdates provides updates,
            LocalKompotDegradationSink provides sink,
        ) {
            // THE HANDLER IS THE HOLDER'S, because navigation is. A source constructed with its own
            // handler could not move the screen it is a source for — which is why `render` takes one
            // rather than the source keeping it.
            val handle: (KompotAction) -> Unit = { action ->
                val destination = (action as? NavigateAction)?.let { routes[it.deeplink] }
                if (destination != null) {
                    // Clearing the overlay is not optional. It is keyed by component id and the ids
                    // of two different screens can collide — `counter-data` on one and on another —
                    // so an update recorded before a move would shadow a node on the screen after it.
                    updates.clear()
                    current = destination
                } else {
                    onAction(action)
                }
            }

            tree?.let { screens.render(it, handle) }
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

    // THE BRAND KIT, and it is on the source rather than left to each entry point on purpose. Every
    // root would otherwise have to remember to fetch it, and a root that forgot would draw the
    // application in Material's default purple — which looks like a design decision rather than like
    // a missing call. `null` when the deployment serves none.
    // A FORM ENDPOINT ANSWERS A SCHEMA AND A TREE, and `fetch` can only decode the tree. Separate
    // rather than a nullable field on the same result, because a caller that wants a form wants the
    // schema too — and one that wants a screen must not have to check.
    suspend fun fetchForm(address: String): KompotFormResponse

    // How that form asks the server to recompute what only the server may compute. See
    // `KonektFormScreen`.
    fun patchFetcher(
        address: String,
        formId: String,
    ): PatchFetcher

    suspend fun brandTheme(): KompotTheme?

    fun updates(topic: String): Flow<ComponentUpdate>

    val streamRestarted: Flow<Unit>

    @Composable
    fun render(
        tree: KompotComponent,
        onAction: (KompotAction) -> Unit,
    )
}

data class ComponentUpdate(
    val componentId: String,
    val component: KompotComponent,
)
