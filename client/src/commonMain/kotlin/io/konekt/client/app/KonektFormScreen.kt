package io.konekt.client.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.KompotScreen
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// A FORM, WHICH IS THE ONE SCREEN SHAPE THAT NEEDS MORE THAN A TREE.
//
// Every other screen in this client is a component tree and an action handler. A form is a tree plus
// a `FormSchema` plus a controller that holds the values, validates them locally and — the reason
// this file exists — asks the server for a patch when a field that matters to the server changes.
//
// THE CONTROLLER IS REMEMBERED ON THE SCHEMA'S FORM ID, and that is the whole mechanism. A controller
// rebuilt on every recomposition is a form that forgets what has been typed into it; one rebuilt when
// the response is refetched is the redraw B-20's first acceptance criterion exists to avoid. Keyed on
// the form rather than on the response, so a patch arriving changes values inside a controller that
// survives.
@Composable
fun KonektFormScreen(
    response: KompotFormResponse,
    registry: KompotRegistry,
    // How to ask the server what the form is now worth. `null` for a form with nothing computed —
    // then `requestPatchIfNeeded` quietly does nothing, which is the toolkit's own behaviour.
    patchFetcher: PatchFetcher? = null,
    onAction: (io.github.youndie.kompot.KompotAction) -> Unit = {},
) {
    // The controller's own scope, so a patch request in flight is cancelled with the screen rather
    // than outliving it. The toolkit defaults to a standalone scope and says outright that a real
    // screen should pass its own.
    //
    // BUILT ON `Dispatchers.Default` AND DISPOSED BY HAND, not `rememberCoroutineScope()`. That
    // helper inherits the composition's own context, which is `Dispatchers.Main` — and a patch is
    // network work with no business on the main thread. It also made this screen untestable: a
    // Compose test harness with `kotlinx-coroutines-test` on the classpath and no `setMain` throws
    // the moment `Dispatchers.Main` is touched, which is how this was found.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) {
        onDispose { scope.cancel() }
    }
    val controller =
        remember(response.schema.formId, scope) {
            FormController(
                schema = response.schema,
                patchFetcher = patchFetcher,
                scope = scope,
            )
        }

    KompotScreen(
        rootComponent = response.screen,
        registry = registry,
        formController = controller,
        actionHandler = KompotActionHandler { action -> onAction(action) },
    )
}
