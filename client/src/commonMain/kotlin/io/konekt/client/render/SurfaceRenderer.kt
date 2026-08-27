package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceTones

// THE ONLY CONTAINER KONEKT DRAWS, and it draws its children through the registry rather than
// knowing anything about them.
//
// That is the whole of the difference from the other nine: they are leaves and compose their own
// content, this one composes whatever it was sent. `LocalKompotRegistry` rather than a registry
// passed in, for the reason the holder gives about its own — a renderer holding a registry would be
// a second opinion about which renderer draws what.
class SurfaceRenderer : KompotComponentRenderer<SurfaceComponent> {
    @Composable
    override fun Render(
        component: SurfaceComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val registry = LocalKompotRegistry.current

        // THE SHAPE IS THE CLIENT'S, AND THAT IS THE POINT OF THE COMPONENT EXISTING. The wire has no
        // radius and deliberately never will (research §1.2, D2): brand B changes `lg` 36→22 in a
        // client release, which is what makes a shape swap cheap instead of a coordinated one. So the
        // server says WHAT this is and the design system says what a card's corner looks like here.
        //
        // The fallback is the same one `PlanCardRenderer` uses, and it is reached when the served kit
        // carries no surface — a deployment in the default palette is a coherent thing to be.
        val shape = designSystem.resolveSurface(KompotSurfaceRoles.Container).shape ?: RoundedCornerShape(20.dp)

        // AN UNKNOWN TONE DRAWS THE NEUTRAL CARD rather than nothing, which is the rule every open
        // string in this dictionary follows: a server one release ahead may name a ground this build
        // has never heard of, and the card is still the right card with the wrong fill.
        val ground =
            when (component.tone) {
                SurfaceTones.ACCENT -> M3Colors.PrimaryContainer
                else -> M3Colors.SurfaceVariant
            }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // CLIP BEFORE BACKGROUND. The other way round paints a rectangle and then clips
                    // the layout, leaving square corners of ground behind rounded content — which is
                    // exactly what `KompotModifierNode.Background` does today, and the reason this
                    // component exists at all (U14).
                    .clip(shape)
                    .background(designSystem.resolveColor(ground))
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(component.spacing.dp),
        ) {
            component.children.forEach { child ->
                registry.RenderNode(
                    component = child,
                    actionHandler = actionHandler,
                    formController = formController,
                )
            }
        }
    }
}
