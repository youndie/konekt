package io.konekt.client.app

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.KompotScreen
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.konekt.client.realtime.SseRealtimeSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val onAction: (KompotAction) -> Unit,
) : ScreenSource {
    override suspend fun fetch(address: String): KompotComponent =
        json.decodeKompotComponent(http.get(address).bodyAsText())

    override fun updates(topic: String): Flow<ComponentUpdate> =
        realtime.subscribe(topic).map { ComponentUpdate(it.componentId, it.component) }

    override val streamRestarted: Flow<Unit> get() = realtime.streamRestarted

    @Composable
    override fun render(tree: KompotComponent) {
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
