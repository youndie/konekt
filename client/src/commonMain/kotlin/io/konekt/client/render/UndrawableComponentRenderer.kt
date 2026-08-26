package io.konekt.client.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotDegradationKind
import io.github.youndie.kompot.LocalKompotDegradationSink
import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.form.FormController
import io.konekt.client.app.KonektDegradationSink

// A TYPE IN KONEKT'S OWN DICTIONARY WITH NO RENDERER, and until B-44 that was a hole in the floor.
//
// The forward-compatibility argument covers a type this build has never HEARD of: it decodes into
// `UnknownComponent`, `UnknownBlockRenderer` draws the block, the sink counts it, `originalType`
// reaches tracy indexed. Every part of that works.
//
// A type in the dictionary with no entry in the registry does none of it. It decodes into its own
// class, `KompotRegistry.RenderNode` finds no renderer, and what appears is the toolkit's own
// fallback — red text saying "Unknown component". It is not an `UnknownComponent`, so it reaches no
// block, no sink and no record: from an operator's side that screen is silent.
//
// **It shipped.** `banner` sat in the unrendered list and the home screen sends one to every
// subscriber with no counters, so the first screen every subscriber sees drew a red error. Every test
// missed it by topping up first.
//
// THE TWO FAILURES ARE THE SAME ON SCREEN AND DIFFERENT IN THE RECORD. A subscriber meets the same
// block and the same sentence, because "update to see it" is the only move either leaves them. An
// operator must be able to tell them apart, because one says the client is behind the server and the
// other says this build shipped a dictionary entry it never wired up — see `KonektDegradation.Cause`.
class UndrawableComponentRenderer<T : KompotComponent>(
    // The wire name, passed rather than derived: the class knows its `@SerialName` only through the
    // serializer, and a renderer reaching for one to name itself would be doing at render time what
    // the registry already did at registration time.
    private val wireName: String,
) : KompotComponentRenderer<T> {
    @Composable
    override fun Render(
        component: T,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val sink = LocalKompotDegradationSink.current

        // INSIDE A `LaunchedEffect`, for the reason `UnknownBlockRenderer` learned the hard way: called
        // in the composable body it fires on every recomposition, and the count an operator reads
        // becomes a function of how often Compose redrew rather than of how many components failed.
        LaunchedEffect(component.id, wireName) {
            when (sink) {
                is KonektDegradationSink -> sink.onUndrawable(wireName)

                // Some other sink: it still hears about the component and cannot be told which of the
                // two happened. Better than silence, which is what this was.
                else -> sink.onUnknown(KompotDegradationKind.UNKNOWN_COMPONENT, wireName, drawnAsFallback = false)
            }
        }

        // THE SAME BLOCK, drawn by the same renderer through a synthetic `UnknownComponent`. Not a
        // copy of its Card and Line: two placeholders that drift apart is a screen that says one thing
        // in one place and another in the next, and the density this reads is the container's either
        // way.
        UnknownBlockRenderer(reports = false).Render(
            component = UnknownComponent(id = component.id, originalType = wireName),
            actionHandler = actionHandler,
            formController = formController,
        )
    }
}
