package io.konekt.client.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.KompotScreen
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.forms.SubmitFormAction
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
    // WHERE A `submit_form` POSTS ITS VALUES. The action carries a form id and no address — the
    // toolkit deliberately leaves the routing to the application — so the caller says which id goes
    // where. `null` means this form cannot be submitted, which is the custom package's case: it is
    // priced by patches and bought elsewhere.
    submit: (suspend (formId: String, values: Map<String, io.github.youndie.kompot.form.FieldValue>) -> Unit)? = null,
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

    // A PRESS COUNT, for the reason `KonektApp` has one: a `LaunchedEffect` keyed on the pending
    // action clears its own key and cancels the request before it answers.
    var presses by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<SubmitFormAction?>(null) }

    LaunchedEffect(presses) {
        val action = pending ?: return@LaunchedEffect
        // `getPayload` IS THE VALIDATION, and that is the toolkit's design rather than a shortcut: it
        // returns null when any VISIBLE field has an error, and otherwise the visible fields that hold
        // values. Posting `getRawValues()` instead would send a hidden field's value and would post an
        // invalid form — which makes the client-side rules decoration, and they exist precisely so a
        // subscriber is told about an empty field without a round trip.
        //
        // `markAllAsChanged` first, because a field nobody has touched has no error yet: a form
        // submitted untouched would otherwise be "valid" and empty.
        controller.markAllAsChanged()
        val payload = controller.getPayload() ?: return@LaunchedEffect
        submit?.invoke(action.formId, payload)
    }

    KompotScreen(
        rootComponent = response.screen,
        registry = registry,
        formController = controller,
        actionHandler =
            KompotActionHandler { action ->
                if (action is SubmitFormAction) {
                    pending = action
                    presses += 1
                } else {
                    onAction(action)
                }
            },
    )
}
