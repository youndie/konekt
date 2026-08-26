package io.konekt.client.app

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.KompotScreen
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormPatch
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.client.realtime.SseRealtimeSource
import io.konekt.feature.theme.shared.api.BrandTheme
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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

// THE REAL SOURCE BEHIND THE HOLDER: four operations over the client this module already builds, and
// deliberately nothing else. It is not a repository abstraction hiding HTTP — every method here is
// something `KonektApp` does to the outside world, and the interface exists so a test can drive the
// clear-then-refetch sequence without a socket.
class KonektScreenSource(
    private val http: HttpClient,
    private val realtime: SseRealtimeSource,
    private val registry: KompotRegistry,
    private val json: Json,
) : ScreenSource {
    override suspend fun fetch(address: String): KompotComponent =
        json.decodeKompotComponent(http.get(address).bodyAsText())

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
