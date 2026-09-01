package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography

// How much room the block takes. Only whoever is drawing the tree knows which is right — a card in
// the middle of a list is worse than a line where a card was — so it is a decision the screen makes
// rather than one this renderer guesses from the data.
//
// The default is the LINE, the conservative half: a block that appears among known rows must not push
// them off the screen, and a screen whose subject is unknown is rarer than a row that is.
enum class UnknownBlockDensity {
    LINE,
    CARD,
}

val LocalUnknownBlockDensity = staticCompositionLocalOf { UnknownBlockDensity.LINE }

// What this build draws where it meets a component it has never heard of.
//
// THE TOOLKIT'S DEFAULT DRAWS NOTHING. With no fallback named by the server, `UnknownComponentRenderer`
// reports and returns — which is the right default for a toolkit and a hole in a product. The canvas
// is explicit: *"never a blank gap"*, because a hole is indistinguishable from a screen that failed
// to load, and the subscriber's next move is to ring support about a feature they cannot see.
//
// Replacing it costs one entry in the registry, which is a plain map: `kompotCoreRenderers +
// konektRenderers` puts ours last and last wins. No fork, and the toolkit's reporting is kept —
// this defers to `UnknownComponentRenderer` whenever the server DID name an equivalent, because
// drawing that equivalent is better than drawing our apology over it.
class UnknownBlockRenderer(
    private val toolkit: KompotComponentRenderer<UnknownComponent> =
        io.github.youndie.kompot
            .UnknownComponentRenderer(),
    // WHETHER THIS RENDERER REPORTS, and `false` has exactly one caller.
    // `UndrawableComponentRenderer` draws this block through a synthetic `UnknownComponent` and has
    // already reported the degradation itself — with the cause kompot's sink cannot carry. Left at
    // `true` there, one component produces TWO records saying different things about the same
    // failure, and the count an operator reads doubles for half the screen.
    private val reports: Boolean = true,
) : KompotComponentRenderer<UnknownComponent> {
    @Composable
    override fun Render(
        component: UnknownComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        // A fallback the server named is what it wanted drawn, and it knows what its component stood
        // in for. Deferring keeps that path — and the toolkit's own report of it — exactly as it is.
        if (component.fallback != null) {
            toolkit.Render(component, actionHandler, formController)
            return
        }

        // Reported through the toolkit's sink rather than a channel of ours, so a deployment sets one
        // sink and hears about all three kinds. `drawnAsFallback = false`: we drew a placeholder, not
        // the thing itself, and a hole and a placeholder are different facts about a screen.
        //
        // INSIDE A `LaunchedEffect`, AND THAT IS THE FIX FOR A REAL DEFECT. Called in the composable
        // body it fired on every RECOMPOSITION, so the count an operator reads was a function of how
        // often Compose redrew rather than of how many components failed to render. The test that
        // claimed "reported once" passed because its fixture composed once and never again — it was
        // right about the number and wrong about the reason, which is the shape worth catching.
        //
        // Keyed by the component rather than by nothing: a node replaced by a live update is a
        // different degradation and should be counted again, while a redraw of the same one is not.
        val sink = io.github.youndie.kompot.LocalKompotDegradationSink.current
        LaunchedEffect(component.id, component.originalType, reports) {
            if (!reports) return@LaunchedEffect
            sink.onUnknown(
                io.github.youndie.kompot.KompotDegradationKind.UNKNOWN_COMPONENT,
                component.originalType,
                drawnAsFallback = false,
            )
        }

        when (LocalUnknownBlockDensity.current) {
            UnknownBlockDensity.CARD -> Card()
            UnknownBlockDensity.LINE -> Line()
        }
    }

    // THE COPY IS THE CANVAS'S, and it is doing two jobs: it says the screen is fine and it says what
    // to do. "Update to see it" is the only action a subscriber has, and a block that does not say so
    // is a block they report as a bug.
    @Composable
    private fun Card() {
        val designSystem = LocalKompotDesignSystem.current
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(surface.shape ?: CardGeometry.Shape)
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .border(
                        1.dp,
                        designSystem.resolveColor(M3Colors.Outline),
                        surface.shape ?: CardGeometry.Shape,
                    ).padding(CardGeometry.Inset),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = HEADLINE,
                style = designSystem.resolveTypography(M3Typography.TitleSmall),
                color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
            )
            Text(
                text = BODY,
                style = designSystem.resolveTypography(M3Typography.BodySmall),
                color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
            )
        }
    }

    @Composable
    private fun Line() {
        val designSystem = LocalKompotDesignSystem.current

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = LINE_TEXT,
                style = designSystem.resolveTypography(M3Typography.BodySmall),
                color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                // One line, whatever the width. A placeholder that wraps to three is a placeholder
                // that pushes the rows it sits among off the screen.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    internal companion object {
        // NO `originalType` ON THE SCREEN. It goes to the sink, where an operator can count it; on the
        // screen it is a wire name a subscriber cannot act on, and the canvas's copy deliberately says
        // what to do instead of what is missing.
        const val HEADLINE = "Something new is here"
        const val BODY =
            "The server sent a component this build does not know. " +
                "Everything around it still works — update to see it."
        const val LINE_TEXT = "An item here needs a newer version of the app."
    }
}
