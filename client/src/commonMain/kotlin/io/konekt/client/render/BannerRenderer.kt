package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones

// THE FIRST SCREEN A NEW SUBSCRIBER SEES WAS DRAWING A RED ERROR, and nothing in this repository knew.
//
// `HomeScreen` sends a `banner` when a subscriber has no counters — "No plan is active on this line
// yet", with somewhere to go — precisely so an empty home screen is not indistinguishable from one
// that failed to load. `banner` was in the dictionary and had no renderer, and a component that
// DECODES and cannot be DRAWN is not an `UnknownComponent`: it never reaches konekt's degradation
// block, so what appeared was the registry's own fallback, in red, saying "Unknown component".
//
// Every test missed it for one reason: they all top up and buy first, so the subscriber always has a
// counter and the banner is never sent. It took running the application on a phone against a stand,
// with a fresh account, to see the state a real first-time subscriber gets.
//
// THE LESSON IS NOT "ADD A RENDERER". It is that the degradation story covers types the client cannot
// decode and says nothing about types it can decode and cannot draw — the second is invisible from
// both `KonektRendererCoverageTest`, which knows `banner` is unrendered and treats that as a fact
// rather than a defect, and from the sink, which never hears about it.
class BannerRenderer : KompotComponentRenderer<BannerComponent> {
    @Composable
    override fun Render(
        component: BannerComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)

        // THE TONE PICKS A COLOUR ROLE AND NOTHING ELSE. An unknown tone draws the neutral banner —
        // the component's own comment says why: the message still reaches the subscriber, which
        // matters more than its colour.
        val accent =
            when (component.tone) {
                MessageTones.LOW -> M3Colors.Secondary
                MessageTones.ERROR -> M3Colors.Error
                else -> M3Colors.Primary
            }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(surface.shape ?: RoundedCornerShape(20.dp))
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .border(1.dp, designSystem.resolveColor(accent), surface.shape ?: RoundedCornerShape(20.dp))
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = component.text,
                style = designSystem.resolveTypography(M3Typography.BodyMedium),
                color = designSystem.resolveColor(M3Colors.OnSurface),
            )

            // The action is drawn only when the server sent BOTH a label and an action. A button with
            // no action does nothing and a label-less action cannot be pressed on purpose; either
            // alone is a server mistake this client should not paper over with a guess.
            val label = component.actionText
            val action = component.action
            if (label != null && action != null) {
                TextButton(onClick = { actionHandler.handle(action) }) {
                    Text(
                        text = label,
                        style = designSystem.resolveTypography(M3Typography.LabelLarge),
                        color = designSystem.resolveColor(accent),
                    )
                }
            }
        }
    }
}
