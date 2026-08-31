package io.konekt.client.app

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.KompotScreen
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormPatch
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.navigation.NavigationGraph
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.feature.shell.shared.api.NavigationResource
import io.konekt.feature.theme.shared.api.BrandTheme
import io.ktor.client.HttpClient
import io.ktor.client.plugins.resources.get
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// THE REAL SOURCE BEHIND THE HOLDER: four operations over the client this module already builds, and
// deliberately nothing else. It is not a repository abstraction hiding HTTP — every method here is
// something `KonektApp` does to the outside world, and the interface exists so a test can drive the
// clear-then-refetch sequence without a socket.
class KonektScreenSource(
    private val http: HttpClient,
    private val realtime: SseRealtimeSource,
    private val registry: KompotRegistry,
    private val json: Json,
    // WHERE EACH FORM POSTS. `submit_form` carries a form id and no address — the toolkit leaves the
    // routing to the application — so this is the client's half of that contract, and a map for the
    // same reason `routes` is: the deployment decides, not the library.
    private val submits: Map<String, String> = emptyMap(),
    // WHERE A FORM ASKS FOR A RECOMPUTE. Empty by default like `submits`, and a form that is not in
    // it is drawn without a fetcher — see `KonektRoutes.patches`.
    private val patches: Map<String, String> = emptyMap(),
) : ScreenSource {
    // ONE REQUEST, AND THE BODY DECIDES THE SHAPE. A form response is an object with `schema` and
    // `screen`; a screen is a component with a `type`. Choosing on the presence of `schema` rather
    // than on the address means the client needs no second copy of which routes are forms — the
    // server already said so by what it sent.
    override suspend fun fetch(address: String): Screen {
        val body = http.get(address).bodyAsText()

        // THE ADDRESS AND THE BODY IN THE MESSAGE, because a decoder's own is neither. "Unexpected
        // JSON token at offset 13" names no route and shows nothing, and the first thing anybody does
        // with it is add a println — so it is here instead.
        val root =
            try {
                json.parseToJsonElement(body).jsonObject
            } catch (failure: SerializationException) {
                throw SerializationException("$address did not answer one JSON document: ${body.take(200)}", failure)
            }

        return if (root.containsKey("schema")) {
            Screen.Form(json.decodeFromString(KompotFormResponse.serializer(), body))
        } else {
            Screen.Tree(json.decodeKompotComponent(body))
        }
    }

    // A missing kit is `null` and not a failure. A deployment that serves no theme is a deployment
    // in the default palette, which is a coherent thing to be; making it fatal would stop an
    // application from starting over a colour.
    // A FORM, WHICH IS NOT A TREE. `fetch` decodes a `KompotComponent`; a form endpoint answers a
    // `KompotFormResponse` — a schema and a tree — and decoding one as the other loses the half that
    // makes the fields work.
    override suspend fun fetchForm(address: String): KompotFormResponse =
        json.decodeFromString(KompotFormResponse.serializer(), http.get(address).bodyAsText())

    // The patch, in the shape `FormController` asks for: it hands over the field that moved and the
    // whole form as it currently stands, and expects the changes back.
    //
    // THE SERVER IS SENT THE SNAPSHOT AND TRUSTED WITH NOTHING FROM IT that it computes itself — the
    // price travels in this payload and is recomputed on arrival. Sending it is how the toolkit's
    // contract works, not a claim that it means anything.
    // The form id is a PARAMETER rather than something read out of `values`: the toolkit's
    // `PatchFetcher` is `(fieldId, values) -> FormPatch` and carries no form id at all, so the caller
    // — which holds the schema — supplies it.
    override fun patchFetcher(
        address: String,
        formId: String,
    ): PatchFetcher =
        { fieldId, values ->
            val body = FormPatchRequest(formId = formId, fieldId = fieldId, values = values)
            json.decodeFromString(
                FormPatch.serializer(),
                http
                    .post(address) {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(FormPatchRequest.serializer(), body))
                    }.bodyAsText(),
            )
        }

    // THE NEXT PAGE, fetched with the same client and decoded with the same `Json` as everything else.
    //
    // The toolkit hands over a path and the parameters it wants appended, and asks for a
    // `KompotPageResponse` back — so this is four lines and no route table. What it is NOT is
    // optional: `PaginatedListRenderer` reads the loader out of a composition local and throws when
    // there is none, whether or not the list has a second page to ask for. The orders screen said so
    // the first time anything opened it.
    // An object expression rather than a lambda: `KompotPageLoader` is not a `fun interface` — its
    // one method carries a default argument, which is what stops it being one.
    override fun pages(): KompotPageLoader =
        object : KompotPageLoader {
            override suspend fun loadPage(
                url: String,
                params: Map<String, String>,
            ): KompotPageResponse {
                val response =
                    http.get(url) {
                        params.forEach { (name, value) -> parameter(name, value) }
                    }
                return json.decodeFromString(KompotPageResponse.serializer(), response.bodyAsText())
            }
        }

    // THE ROUTE TABLE, FETCHED. `B-49`'s last acceptance criterion: a deeplink is resolved through the
    // graph the SERVER publishes rather than through a copy the client wrote — the copy is the one
    // place a deployment could change its destinations and the client would not follow.
    //
    // `null` rather than an exception on a refusal, and the refusal is the ordinary case: the graph
    // sits behind the user tier, and the application opens on the login screen with no session. The
    // holder keeps whatever it had, which before a session is the two screens that ARE the way in.
    override suspend fun navigation(): Map<String, String>? =
        try {
            json
                .decodeFromString(NavigationGraph.serializer(), http.get(NavigationResource()).bodyAsText())
                .routes
                .associate { it.deeplink to it.endpoint }
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }

    override suspend fun brandTheme(): KompotTheme? =
        // A PRECISE `try`, never `runCatching`. Plain `runCatching` swallows `CancellationException`,
        // and this runs inside a `LaunchedEffect` — so a composable leaving the tree would find its
        // cancellation eaten here, which is a coroutine that will not stop and nobody attributing it
        // to a theme fetch. `RunCatchingUsageTest` refuses it outright, and refused this.
        try {
            json.decodeFromString(KompotTheme.serializer(), http.get(BrandTheme.PATH).bodyAsText())
        } catch (e: IOException) {
            // The deployment serves no kit, or cannot be reached for one. Neither is worth failing to
            // start over: a client in the default palette is a coherent thing to be.
            null
        } catch (e: SerializationException) {
            // A kit that arrived and is not one. Same answer, different cause, and both are worth
            // catching by name rather than by `Throwable`.
            null
        }

    override fun updates(topic: String): Flow<ComponentUpdate> =
        realtime.subscribe(topic).map { ComponentUpdate(it.componentId, it.component) }

    override val streamRestarted: Flow<Unit> get() = realtime.streamRestarted

    @Composable
    override fun render(
        screen: Screen,
        onAction: (KompotAction) -> Unit,
    ) {
        when (screen) {
            // A FORM GETS ITS OWN CONTROLLER, built from the schema that came with it. The empty one
            // below is what every other screen gets, and a form drawn with it holds nothing: the
            // inputs render, accept typing, and lose it.
            is Screen.Form -> {
                KonektFormScreen(
                    response = screen.response,
                    registry = registry,
                    // THE FETCHER THE APPLICATION FORGOT. This read `null` while the implementation
                    // below was written, commented and called from nowhere, so the custom package's
                    // price sat at `$0` whatever anybody chose — the server recomputed correctly and
                    // the answer had no way back to the screen (`B-101`).
                    //
                    // Null when the form has no patch address, which is the login form and is correct
                    // for it: a fetcher there would be a round trip nothing asks for.
                    patchFetcher =
                        patches[screen.response.schema.formId]?.let {
                            patchFetcher(it, screen.response.schema.formId)
                        },
                    onAction = onAction,
                    // SUBMITTING GOES BACK OUT THROUGH THE SAME HANDLER. The endpoint answers a
                    // `KompotAction` — a `navigate` for the first login step, an `update_session` for
                    // the second — and the client feeds it into the chain it already has. Nothing new
                    // travels back and no new endpoint kind appears; that is the toolkit's own design,
                    // and the reason a form submit needs no machinery of its own here.
                    submit = { formId, values ->
                        val address =
                            submits[formId]
                                ?: error("no address for form \"$formId\" — a submit button posting nowhere")

                        val answer =
                            json.decodeKompotAction(
                                http
                                    .post(address) {
                                        contentType(ContentType.Application.Json)
                                        setBody(
                                            json.encodeToString(
                                                FormPatchRequest.serializer(),
                                                // `fieldId` is what CHANGED, and a submit changes
                                                // nothing — the form is the subject. Sending the form's
                                                // own id rather than inventing a field keeps the shape
                                                // one thing rather than two.
                                                FormPatchRequest(formId = formId, fieldId = formId, values = values),
                                            ),
                                        )
                                    }.bodyAsText(),
                            )
                        onAction(answer)
                    },
                )
            }

            is Screen.Tree -> {
                renderTree(screen.component, onAction)
            }
        }
    }

    @Composable
    override fun renderNode(
        component: KompotComponent,
        onAction: (KompotAction) -> Unit,
    ) {
        registry.RenderNode(
            component = component,
            actionHandler = KompotActionHandler { action -> onAction(action) },
            formController = FormController(FormSchema(formId = "konekt-shell", fields = emptyList())),
        )
    }

    @Composable
    private fun renderTree(
        tree: KompotComponent,
        onAction: (KompotAction) -> Unit,
    ) {
        KompotScreen(
            rootComponent = tree,
            registry = registry,
            // AN EMPTY FORM CONTROLLER FOR A SCREEN WITH NO FORM, because the toolkit asks for one
            // either way: `KompotScreen` and `RenderNode` both take a `FormController` whether or not
            // the tree contains a field. So a client that renders anything at all depends on the form
            // vocabulary — which is the one thing the toolkit's own module split says it should not
            // have to (`kompot-forms` is a plug-in over `kompot-core`, and a profile of core plus
            // standard is supposed to need no form vocabulary at all).
            //
            // Worth an upstream note rather than a workaround: this costs a dependency, not
            // correctness.
            formController = FormController(FormSchema(formId = "konekt", fields = emptyList())),
            actionHandler = KompotActionHandler { action -> onAction(action) },
        )
    }
}
