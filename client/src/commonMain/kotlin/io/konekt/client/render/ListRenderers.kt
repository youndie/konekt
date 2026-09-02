package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.EsimCardComponent
import io.konekt.components.EsimStatuses
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceDensities
import io.konekt.components.SurfaceTones

// One line of the operation history.
//
// THE STATUS WORD IS THE SERVER'S AND SO IS ITS SENTENCE. `status` picks a colour role and
// `statusText` is what a subscriber reads — which is the whole reason petich's `FAILED` never reaches
// a screen: a cleanly rolled-back saga is `compensated` here, and telling somebody "failed" would be
// wrong twice over.
class OrderRowRenderer : KompotComponentRenderer<OrderRowComponent> {
    @Composable
    override fun Render(
        component: OrderRowComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val registry = LocalKompotRegistry.current
        val action = component.action

        // THE STATUS IS A CHIP ON THE RIGHT (`B-114`, block 4), in the ground the canvas gives each
        // word: mint for a purchase that went through, red-tinted for money that came back, grey for
        // everything else — and a word this build has never heard of gets the grey chip, not none.
        val tone =
            when (component.status) {
                OrderStatuses.COMPLETED -> SurfaceTones.ACCENT
                OrderStatuses.COMPENSATED, OrderStatuses.REJECTED -> SurfaceTones.ALERT
                else -> SurfaceTones.NEUTRAL
            }
        val wordColour =
            when (tone) {
                SurfaceTones.ACCENT -> M3Colors.OnPrimaryContainer
                SurfaceTones.ALERT -> M3Colors.OnErrorContainer
                else -> M3Colors.OnSurfaceVariant
            }
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(surface.shape ?: RoundedCornerShape(20.dp))
                    .background(designSystem.resolveColor(M3Colors.Surface))
                    .then(if (action != null) Modifier.clickable { actionHandler.handle(action) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = component.title,
                    style = designSystem.resolveTypography(M3Typography.TitleMedium),
                    color = designSystem.resolveColor(M3Colors.OnSurface),
                    modifier = Modifier.weight(1f, fill = false),
                )
                component.statusText?.let { word ->
                    // Through the registry, so the chip is the chip every other screen draws.
                    registry.RenderNode(
                        component =
                            SurfaceComponent(
                                id = "${component.id}-status",
                                tone = tone,
                                density = SurfaceDensities.CHIP,
                                children =
                                    listOf(
                                        TextComponent(
                                            id = "${component.id}-status-text",
                                            text = word,
                                            style = M3Typography.LabelMedium,
                                            color = wordColour,
                                        ),
                                    ),
                            ),
                        actionHandler = actionHandler,
                        formController = formController,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        listOf(
                            component.reference,
                            component.dateText,
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = designSystem.resolveTypography(M3Typography.LabelMedium),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                )
                // THE SIGNED AMOUNT stays — it is not in the canvas and it is useful — on the second
                // line, where it reads as the ledger's figure rather than as the card's headline.
                Text(
                    text = component.amountText,
                    style = designSystem.resolveTypography(M3Typography.LabelMedium),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                )
            }
            component.noteText?.let {
                Text(
                    text = it,
                    style = designSystem.resolveTypography(M3Typography.BodySmall),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

class EsimCardRenderer : KompotComponentRenderer<EsimCardComponent> {
    @Composable
    override fun Render(
        component: EsimCardComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)
        val shape = CardGeometry.shapeOf(CardGeometry.Tier.ITEM)
        val action = component.action

        // The two terminal states are separate words because they mean opposite things: one can be
        // resumed and the other cannot.
        val accent =
            when (component.status) {
                EsimStatuses.INSTALLED -> M3Colors.Primary
                EsimStatuses.READY, EsimStatuses.ORDERED -> M3Colors.Secondary
                else -> M3Colors.OnSurfaceVariant
            }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(designSystem.resolveColor(M3Colors.Surface))
                    .border(1.dp, designSystem.resolveColor(M3Colors.Outline), shape)
                    .then(if (action != null) Modifier.clickable { actionHandler.handle(action) } else Modifier)
                    .padding(CardGeometry.Tier.ITEM.inset),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = component.label,
                style = designSystem.resolveTypography(M3Typography.TitleSmall),
                color = designSystem.resolveColor(M3Colors.OnSurface),
            )
            Text(
                text = component.iccid,
                style = designSystem.resolveTypography(M3Typography.BodySmall),
                color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
            )
            Text(
                text = component.statusText,
                style = designSystem.resolveTypography(M3Typography.LabelMedium),
                color = designSystem.resolveColor(accent),
            )
        }
    }
}
