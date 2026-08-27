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
import io.github.youndie.kompot.LocalKompotPageLoader
import io.github.youndie.kompot.LocalKompotRealtimeUpdates
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.KompotPageLoader
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
    val HANDLES_NOTHING: suspend (KompotAction) -> String? = { null }
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
    // Everything that is not a transition, and it may ANSWER WITH AN ADDRESS.
    //
    // `routes` is the synchronous half — a deeplink the server chose in advance. This is the other:
    // an action whose destination is not knowable until it has happened. Buying is the example and
    // the reason the shape is this one — a purchase creates an order, and where the subscriber goes
    // next is that order's screen. The holder still owns the movement; what it does not own is the
    // verb, which is why the answer comes back rather than the holder posting anything.
    //
    // `null` means the action was handled and moves nothing, or was not handled at all. A handler that
    // silently did nothing would make a button with no handler indistinguishable from one whose
    // handler is missing.
    onAction: suspend (KompotAction) -> String? = KonektApp.HANDLES_NOTHING,
) {
    // THE ADDRESS IS STATE NOW, seeded from the parameter. `remember(address)` on the seed rather
    // than `remember { }`: a caller that changes the address it passes still moves the holder, which
    // is what every existing test does.
    var current by remember(address) { mutableStateOf(address) }
    var screen by remember(current) { mutableStateOf<Screen?>(null) }
    // KEYED BY A PRESS COUNT AND NOT BY THE ACTION, and that is a fix rather than a flourish.
    //
    // A `LaunchedEffect(pending)` that clears `pending` inside itself changes its own key, which
    // cancels the coroutine mid-flight — before the request it was launched to make has answered.
    // Nothing about that looks wrong: the press registers, the effect starts, and the screen simply
    // never moves. Counting the presses gives every one of them a key of its own and needs no
    // clearing at all, so there is nothing to cancel except by pressing again — which is exactly
    // when cancelling is right.
    var presses by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<KompotAction?>(null) }

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

    LaunchedEffect(current) { screen = screens.fetch(current) }

    LaunchedEffect(presses) {
        val action = pending ?: return@LaunchedEffect
        onAction(action)?.let { destination ->
            updates.clear()
            current = destination
        }
    }

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
            screen = screens.fetch(current)
        }
    }

    KonektTheme(theme = theme ?: fetchedTheme, darkMode = darkMode) {
        CompositionLocalProvider(
            LocalKompotRealtimeUpdates provides updates,
            LocalKompotDegradationSink provides sink,
            LocalKompotPageLoader provides screens.pages(),
        ) {
            // THE HANDLER IS THE HOLDER'S, because navigation is. A source constructed with its own
            // handler could not move the screen it is a source for — which is why `render` takes one
            // rather than the source keeping it.
            val handle: (KompotAction) -> Unit = { action ->
                val destination = (action as? NavigateAction)?.let { resolve(it.deeplink, routes) }
                if (destination != null) {
                    // Clearing the overlay is not optional. It is keyed by component id and the ids
                    // of two different screens can collide — `counter-data` on one and on another —
                    // so an update recorded before a move would shadow a node on the screen after it.
                    updates.clear()
                    current = destination
                } else {
                    // HANDED TO AN EFFECT RATHER THAN LAUNCHED HERE. `onAction` suspends — buying is a
                    // request — and a renderer's click handler does not. Routing it through state
                    // means the work is cancelled with the composition rather than outliving it, and
                    // it needs no scope of its own to be cancelled with.
                    pending = action
                    presses += 1
                }
            }

            screen?.let { screens.render(it, handle) }
        }
    }
}

// WHAT THE HOLDER NEEDS, AS AN INTERFACE, so that a test can drive the sequence this application
// exists to get right — a stale overlay, a gap, a refetch — without a server, a socket or a stand.
//
// It is not a repository abstraction hiding HTTP. Every method here is something the holder does to
// the outside world, and the real implementation is a handful of lines over the client this module
// already builds.
// WHAT AN ADDRESS ANSWERS WITH, and there are two shapes rather than one.
//
// Most screens are a component tree. A form is a tree AND a schema, and the difference is not
// cosmetic: the fields only work if a `FormController` built from that schema is in scope, so a
// caller that decoded a form as a tree would draw five inputs that hold nothing.
//
// A sealed type rather than a nullable schema on one class: the holder must handle both, and a
// nullable field is a branch a caller can forget to take.
sealed interface Screen {
    data class Tree(
        val component: KompotComponent,
    ) : Screen

    data class Form(
        val response: KompotFormResponse,
    ) : Screen
}

interface ScreenSource {
    // The address decides which shape comes back, and the SERVER decides that by what it serves. A
    // client that had to be told in advance would be a client with a second copy of the route table.
    suspend fun fetch(address: String): Screen

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

    // HOW A LIST ASKS FOR ITS NEXT PAGE, and it is on the source for the same reason the brand kit
    // is: every entry point would otherwise have to remember to provide it, and one that forgot
    // would not draw a shorter list — the renderer THROWS. `LocalKompotPageLoader not provided` is
    // what the orders screen answered the day a tab made it reachable, which is also the day
    // anybody could have found out.
    fun pages(): KompotPageLoader

    fun updates(topic: String): Flow<ComponentUpdate>

    val streamRestarted: Flow<Unit>

    @Composable
    fun render(
        screen: Screen,
        onAction: (KompotAction) -> Unit,
    )
}

data class ComponentUpdate(
    val componentId: String,
    val component: KompotComponent,
)

// A DEEPLINK TO AN ADDRESS, exact first and then by prefix.
//
// Exact is what `app://plans` needs. The prefix is what `app://login/code?msisdn=+1555…` needs: the
// destination is one screen and the query is a value the server put there, so a map keyed on the whole
// string would need an entry per subscriber. Everything after the matched prefix is carried across
// unchanged — the client does not read it, and reading it would be the client deciding what a login
// step means.
//
// Longest prefix wins, so `app://login/code` is not swallowed by `app://login`. Sorting rather than
// trusting the map's order: a `Map` has none, and the version of this that worked did so by accident
// of insertion.
internal fun resolve(
    deeplink: String,
    routes: Map<String, String>,
): String? {
    routes[deeplink]?.let { return it }

    val prefix =
        routes.keys
            .filter { deeplink.startsWith("$it?") || deeplink.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: return null

    return routes.getValue(prefix) + deeplink.removePrefix(prefix)
}
