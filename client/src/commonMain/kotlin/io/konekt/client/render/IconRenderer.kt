package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.konekt.components.IconComponent
import io.konekt.components.MessageTones

// A SHAPE IN A CIRCLE, coloured by role. The canvas's outcome mark is a white check on a filled
// primary disc; its refusal is the error colour on the error container. Same pairs a banner uses for
// the same words, resolved from the brand kit, so a rebrand repaints these without a client release —
// the shape is data (`VectorIcon`), and the colour is deliberately not.
class IconRenderer : KompotComponentRenderer<IconComponent> {
    @Composable
    override fun Render(
        component: IconComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val (disc, ink) =
            when (component.tone) {
                MessageTones.ERROR -> M3Colors.ErrorContainer to M3Colors.Error
                MessageTones.LOW -> M3Colors.SecondaryContainer to M3Colors.Secondary
                else -> M3Colors.Primary to M3Colors.OnPrimary
            }
        Box(
            modifier =
                Modifier
                    .size(component.size.dp)
                    .clip(CircleShape)
                    .background(designSystem.resolveColor(disc)),
            contentAlignment = Alignment.Center,
        ) {
            // The glyph at half the disc: the canvas's 88-point mark carries a 44-point check, and
            // a stroke of 2 on a 24 grid scales to about 3.5 at that size, which is what it draws.
            VectorIconGlyph(
                icon = component.icon,
                color = designSystem.resolveColor(ink),
                size = (component.size / 2).dp,
            )
        }
    }
}
