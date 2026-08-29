package io.konekt.client.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotDegradationSink
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotPageLoader
import io.github.youndie.kompot.LocalKompotRealtimeUpdates
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.navigation.NavigationBackStack
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
    val HANDLES_NOTHING: suspend (KompotAction) -> Destination? = { null }
}

// WHERE AN ACTION LEFT THE SUBSCRIBER, and how much of the history that answer survives.
//
// A bare address could not say the second half, and the difference is not cosmetic. Signing in and
// confirming a purchase both answer with an address, and back must behave in OPPOSITE ways: the
// order screen you confirmed on still sits above the catalogue you came from, while the home screen
// you signed in to sits above nothing at all — a back control there returns to a login already used,
// and the mirror of it returns to a home screen whose token has just been cleared.
//
// The holder cannot tell the two apart, and must not learn how: a screen holder that knew what a
// session was would be this application's holder rather than a reusable one. So the runner, which
// already knows — it is the thing that adopts and drops the tokens — says which of the two this is.
data class Destination(
    val address: String,
    val arrival: Arrival,
) {
    // THREE ARRIVALS AND NOT TWO, because "clear the stack" and "the session changed" are different
    // facts that happened to want the same thing.
    //
    // They were one boolean, and the third case had nowhere to go: finishing the install wizard
    // needed the stack cleared and the session untouched, so it arrived as a `next` — which replaces
    // only the top and left the order and the catalogue underneath. The subscriber landed on HOME
    // with a back control on it, which is the first defect this application was ever reported for.
    // Reusing `startOver` instead would have refetched the navigation graph on the strength of an
    // eSIM being installed.
    enum class Arrival {
        // A STEP. The screen it came from is replaced rather than pushed behind: an action that
        // answered with an address ENDED somewhere, so back belongs to whatever was under it.
        NEXT,

        // A FLOW IS OVER. Nothing inside it should be reachable — a finished wizard, most of all by
        // the back control — and the session is exactly what it was.
        FLOW_ENDED,

        // A BOUNDARY. Nothing before this is reachable, and nothing before it should be: on one side
        // of a sign-in or a sign-out the tokens are different, so every address behind it answers to
        // a session that no longer exists. The graph is a different answer here too.
        SESSION_CHANGED,
    }

    companion object {
        fun next(address: String): Destination = Destination(address, Arrival.NEXT)

        fun endOfFlow(address: String): Destination = Destination(address, Arrival.FLOW_ENDED)

        fun startOver(address: String): Destination = Destination(address, Arrival.SESSION_CHANGED)
    }
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
    // WHAT IS KNOWN BEFORE THE SERVER IS ASKED, and it is no longer the route table.
    //
    // `B-49`'s last criterion is that a deeplink resolves through the GRAPH the server publishes: a
    // client with its own copy is the one place a deployment could change its destinations and not be
    // followed. The graph sits behind the user tier, though, and this application opens on the login
    // screen with no session — so what is passed here is a BOOTSTRAP: the destinations reachable
    // before there is anything to ask with, which are the two that are the way in.
    //
    // Everything else arrives from `screens.navigation()` and replaces nothing: the fetched table is
    // merged over this one, so a deployment that serves no graph keeps working with what it opened
    // with.
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
    onAction: suspend (KompotAction) -> Destination? = KonektApp.HANDLES_NOTHING,
) {
    // THE ADDRESS IS STATE NOW, seeded from the parameter. `remember(address)` on the seed rather
    // than `remember { }`: a caller that changes the address it passes still moves the holder, which
    // is what every existing test does.
    // A BACK STACK, and it is `kompot-navigation`'s rather than a list of ours.
    //
    // The toolkit has carried `NavigationBackStack` since this build began and nothing used it —
    // push, pop, `canGoBack`, and nothing else, which is exactly the amount of stack this product
    // has. `current` is its top.
    //
    // WHY THERE IS ONE AT ALL: the canvas draws no toolbar and no back control on any of its nine
    // sections — only the bottom bar — so every screen it draws is a screen you arrive at, and the
    // question of leaving one never comes up in a set of states. It comes up immediately in a
    // running application: pressing a plan led to the purchase result, which is not a tab, and there
    // was no way off it.
    var stack by remember(address) { mutableStateOf(NavigationBackStack(address)) }
    val current = stack.current
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
    var reloads by remember { mutableStateOf(0) }

    // WHICH DEEPLINKS ARE TABS, taken from the bar the SERVER sent rather than from a list here.
    // The tab set is a product decision that travels on the wire (`bottom_nav`), so a second copy in
    // the client would be the one place a deployment could change its tabs and its back behaviour
    // would not follow.
    //
    // Remembered ACROSS screens, because the screens that most need the answer are the ones without
    // a bar: the purchase result carries none, and "Done" on it goes to a tab.
    var tabs by remember { mutableStateOf(emptySet<String>()) }

    // THE ROUTE TABLE AS STATE, seeded from the bootstrap and replaced by the served graph.
    var routeTable by remember(routes) { mutableStateOf(routes) }

    // REFETCHED AT A SESSION BOUNDARY AND NOWHERE ELSE, which is exactly when the answer can change:
    // before a session the graph refuses, after one it is available, and after signing out it refuses
    // again. `Arrival.SESSION_CHANGED` is the runner's own word for that moment — the holder does not
    // learn what a token is to use it, and a flow that merely ENDED does not ask, which is why the two
    // are separate arrivals rather than one flag.
    var sessions by remember { mutableStateOf(0) }
    LaunchedEffect(sessions) {
        screens.navigation()?.let { routeTable = routes + it }
    }

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

    // KEYED ON THE ADDRESS **AND** ON A RELOAD COUNT, and the second half is what makes confirming a
    // purchase visible.
    //
    // An action may answer with the address the client is already on: confirming an order ends on the
    // order's own screen, in a different state. Keyed on the address alone this effect does not
    // re-run, so the screen keeps showing "Confirm" while the money has already moved — the button
    // looks broken and the purchase is complete.
    //
    // It is the same shape as the press counter below, and this file already carried that lesson: an
    // effect keyed on a value that does not change does nothing.
    LaunchedEffect(current, reloads) { screen = screens.fetch(current) }

    LaunchedEffect(presses) {
        val action = pending ?: return@LaunchedEffect
        onAction(action)?.let { destination ->
            updates.clear()
            stack =
                when {
                    // NOTHING BEHIND IT. `pop()` on a stack of one returns the stack unchanged — the
                    // toolkit refuses to leave it empty — so the branch below turned the login screen
                    // into the home screen's parent and put a back control on a tab. The mirror of it
                    // put the just-signed-out home screen behind the login screen, one press from a
                    // 401.
                    destination.arrival == Destination.Arrival.SESSION_CHANGED -> {
                        // The graph is a different answer on the other side of this, so ask again.
                        sessions += 1
                        NavigationBackStack(destination.address)
                    }

                    // THE SAME EMPTY STACK AND NO SESSION QUESTION. A finished flow leaves nothing
                    // behind it — that is the whole of what "finished" means here — and the tokens
                    // are the ones it started with, so asking the server for its graph again would be
                    // a request made because an eSIM was installed.
                    destination.arrival == Destination.Arrival.FLOW_ENDED -> {
                        NavigationBackStack(destination.address)
                    }

                    // Where we already are: confirming an order ends on the order's own screen.
                    destination.address == current -> {
                        stack
                    }

                    // REPLACING THE TOP rather than pushing. An action that answers with an address
                    // ENDED somewhere — a purchase confirmed — and pushing would put the screen it
                    // came from behind it, so back would return to a plan already bought.
                    else -> {
                        stack.pop().push(destination.address)
                    }
                }
            // Always, even when the destination is where we already are. What changed is the state
            // behind the address, and only the server knows it.
            reloads += 1
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

    // A NEW SCREENFUL STARTS AT THE TOP, AND AN UPDATED ONE DOES NOT.
    //
    // The scroll state used to be remembered at the call site with no key, so it survived everything:
    // pressing a wizard step replaced the content underneath a position half a screen down, and the
    // banner the subscriber had been waiting for since they paid — "Your eSIM is ready." — arrived
    // above the fold (`B-75`). The address does not change between steps, so keying on it alone was
    // not enough.
    //
    // `reloads` is what tells the two apart, and it already existed. It is bumped in ONE place — the
    // action path, after a press answered with somewhere to be, including the case where that
    // somewhere is where we already are. A live update does not touch it and neither does the
    // refetch after a stream gap, both of which mean "the same screen, newer" and must leave a
    // reader where they were.
    //
    // `key` rather than `remember(...)`, so the state inside stays the saveable one the toolkit
    // gives; what changes is that a new screenful gets a new one.
    val scroll = key(current, reloads) { rememberScrollState() }

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
                // The deeplink AND the address it resolved to, as one value: the stack needs the
                // first to decide whether this was a tab, and the screen needs the second.
                val move =
                    (action as? NavigateAction)?.let { nav ->
                        resolve(nav.deeplink, routeTable)?.let { nav.deeplink to it }
                    }
                if (move != null) {
                    val (deeplink, destination) = move
                    // Clearing the overlay is not optional. It is keyed by component id and the ids
                    // of two different screens can collide — `counter-data` on one and on another —
                    // so an update recorded before a move would shadow a node on the screen after it.
                    updates.clear()
                    // A TAB IS A DESTINATION, NOT A STEP. Pressing one returns to the root of the
                    // stack instead of growing it: four tabs pressed in turn must not become four
                    // presses of back, which is the first thing a bottom bar gets wrong.
                    //
                    // MATCHED BEFORE THE QUERY, and that is not cosmetic. The orders screen grew
                    // filter chips, and each of them is a `navigate` to `app://orders?filter=…` — the
                    // same tab, narrower. Compared whole, none of them is a tab, so three chips
                    // pressed in turn became three presses of back before leaving the screen. A
                    // filtered tab is still the tab.
                    stack =
                        if (deeplink.substringBefore('?') in tabs) {
                            NavigationBackStack(destination)
                        } else {
                            stack.push(destination)
                        }
                } else {
                    // HANDED TO AN EFFECT RATHER THAN LAUNCHED HERE. `onAction` suspends — buying is a
                    // request — and a renderer's click handler does not. Routing it through state
                    // means the work is cancelled with the composition rather than outliving it, and
                    // it needs no scope of its own to be cancelled with.
                    pending = action
                    presses += 1
                }
            }

            // THE FRAME EVERY SCREEN IS DRAWN IN, and it belongs here rather than in a tree.
            //
            // Two things the canvas draws on all nine of its frames and no screen response carries:
            // a side margin, and a bar at the BOTTOM of the window. Neither is a property of a
            // screen — a `padding` modifier on every root would be the server saying the same thing
            // seven times, and a bar drawn where it arrives lands wherever the content happens to
            // end. `B-51`.
            val shell = remember(screen) { (screen as? Screen.Tree)?.component?.withoutShell() }

            // Recorded when a bar arrives, kept when one does not. Written in an effect rather than
            // during composition, which would be a state write in the middle of drawing.
            LaunchedEffect(shell?.nav) {
                shell?.nav?.let { bar ->
                    tabs = bar.items.mapNotNull { (it.action as? NavigateAction)?.deeplink }.toSet()
                }
            }

            // PAINTED, and it was not. `KompotScreen` paints the area it draws in and nothing painted
            // the rest, so the margin around the content and the strip behind the bar were whatever
            // the window happened to be — patches of two colours on one screen, and worse in dark.
            // A frame that owns the window owns its ground.
            //
            // RESOLVED THROUGH THE DESIGN SYSTEM, not read off MaterialTheme. Every renderer in this
            // client asks the design system for its colours, and the served brand kit is what answers
            // — a ground taken from anywhere else is the one surface in the application a brand
            // cannot repaint.
            val content = (screen as? Screen.Tree)?.component ?: shell?.content
            val designSystem = LocalKompotDesignSystem.current

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(designSystem.resolveColor(M3Colors.Surface))
                        // THE INSETS GO **UNDER** THE GROUND AND OVER THE CONTENT, and the order of
                        // these two lines is the whole of it: `background` paints the node's full
                        // size, `windowInsetsPadding` shrinks what is laid out inside it. So the
                        // brand's surface reaches the top and bottom edges of the screen and the
                        // content sits clear of the system bars.
                        //
                        // The first version put this padding OUTSIDE the frame, in the Android
                        // activity. That inset the ground too, so the status and gesture bars showed
                        // the platform theme's window background — a grey strip above a white screen,
                        // in a product whose whole claim is that a served brand repaints everything.
                        //
                        // HERE AND NOT PER PLATFORM because the ground colour is the design system's
                        // and only this frame can ask for it, and because `safeDrawing` is the right
                        // answer on all three: system bars and a cutout on Android, the safe area on
                        // iOS, and zero on a desktop window.
                        //
                        // THAT SENTENCE WAS ONCE MEASURED FALSE AND THE MEASUREMENT WAS THE BROKEN
                        // THING. On the simulator this padding appeared to apply the inset a second
                        // time — the login title sat 55 device pixels lower with it than without — so
                        // it was made a parameter and switched off for iOS. It was not a double
                        // inset: the bundle declared no `UILaunchScreen`, so iOS was running the app
                        // LETTERBOXED, and the safe area of a compatibility canvas is not the safe
                        // area of the screen. With the launch screen declared the app is full size,
                        // the padding is needed on iOS exactly as it is on Android, and the parameter
                        // is gone. A measurement is only as true as the thing it was taken on.
                        .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // THE TOOLBAR, and it holds a back control and nothing else.
                //
                // No title: every screen this build serves draws its own — "Plans", "Profile",
                // "Enter the code" — and a second one in a bar above them would be the same words
                // twice. The day a screen stops titling itself, the graph already carries a title
                // per route to put here.
                //
                // Drawn only when there is somewhere to go, so a tab screen has no empty bar over
                // it. A TEXT ARROW rather than an icon: this client compiles in no icon set, and
                // adding one for a single glyph is a dependency for a character.
                if (stack.canGoBack) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { stack = stack.pop() }) {
                            Text(
                                text = "← Back",
                                style = designSystem.resolveTypography(M3Typography.LabelLarge),
                                color = designSystem.resolveColor(M3Colors.Primary),
                            )
                        }
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // SCROLLED HERE, because nothing below does — see `carriesItsOwnScroll`
                            // for the one case where the frame must keep out of the way, and for why
                            // a screen taller than the window used to be simply cut off.
                            .then(
                                if (content?.carriesItsOwnScroll() == true) {
                                    Modifier
                                } else {
                                    Modifier.verticalScroll(scroll)
                                },
                            ).padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    when {
                        // The tree with its bar taken out. Rendered through the source like any
                        // other, so nothing about the shell reaches the renderers.
                        shell?.nav != null -> screens.render(Screen.Tree(shell.content), handle)

                        else -> screen?.let { screens.render(it, handle) }
                    }
                }

                // Outside the padded box on purpose: the bar runs edge to edge, which is what makes
                // it read as the window's furniture rather than as the last thing on the screen.
                shell?.nav?.let { screens.renderNode(it, handle) }
            }
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

    // WHERE A DEEPLINK GOES, ASKED OF THE SERVER. `null` means the question could not be answered —
    // no session yet, or a deployment that serves no graph — and the holder keeps what it had rather
    // than losing every destination it knows.
    suspend fun navigation(): Map<String, String>?

    // HOW A LIST ASKS FOR ITS NEXT PAGE, and it is on the source for the same reason the brand kit
    // is: every entry point would otherwise have to remember to provide it, and one that forgot
    // would not draw a shorter list — the renderer THROWS. `LocalKompotPageLoader not provided` is
    // what the orders screen answered the day a tab made it reachable, which is also the day
    // anybody could have found out.
    fun pages(): KompotPageLoader

    // ONE COMPONENT, drawn through the same registry as a screen. The shell needs it for the bar it
    // lifted out of the tree, and the registry lives here rather than in the holder — a holder that
    // owned one would be a second opinion about which renderer draws what.
    @Composable
    fun renderNode(
        component: KompotComponent,
        onAction: (KompotAction) -> Unit,
    )

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
