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
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.EsimCardComponent
import io.konekt.components.EsimStatuses
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses

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
        val action = component.action

        // An open vocabulary, and an unknown word draws the neutral row. A history that refused to
        // draw a status it had not met would hide the order rather than the word.
        val accent =
            when (component.status) {
                OrderStatuses.COMPLETED -> M3Colors.Primary
                OrderStatuses.REJECTED -> M3Colors.Error
                OrderStatuses.AWAITING_CONFIRMATION, OrderStatuses.PENDING -> M3Colors.Secondary
                else -> M3Colors.OnSurfaceVariant
            }

        // A SURFACE, which this row did not have. The canvas draws every order as a card (section
        // 05) and the screen drew four lines of text on nothing — which is also why no golden of it
        // could clear `GoldenContentTest`: at 4% drawn a machine cannot tell it from a capture that
        // failed, and it was right not to. `B-51`.
        //
        // The shape comes from the design system rather than a number here, so brand B's tighter
        // radii reach it without a client release — which is the whole claim `B-22` makes.
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(surface.shape ?: RoundedCornerShape(20.dp))
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .then(if (action != null) Modifier.clickable { actionHandler.handle(action) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = component.title,
                    style = designSystem.resolveTypography(M3Typography.BodyLarge),
                    color = designSystem.resolveColor(M3Colors.OnSurface),
                )
                Text(
                    text = component.amountText,
                    style = designSystem.resolveTypography(M3Typography.BodyLarge),
                    color = designSystem.resolveColor(M3Colors.OnSurface),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // THE REFERENCE BESIDE THE DATE — "8f21-4c90 · 12 Aug" on the canvas. It was on the
                // component and drawn nowhere, which made it a field the server composed for nobody:
                // it is the string a subscriber quotes to support, and the one thing on this row
                // that connects it to anything outside the screen.
                Text(
                    // JOINED ONLY WHERE THERE ARE TWO. The purchase-result screen sends an order row
                    // with an EMPTY `dateText` — the order just happened and the screen is about
                    // that, not about when — and a separator written unconditionally drew
                    // "a5906073 · " with nothing after it.
                    text =
                        listOf(
                            component.reference,
                            component.dateText,
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = designSystem.resolveTypography(M3Typography.LabelMedium),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                )
                component.statusText?.let {
                    Text(
                        text = it,
                        style = designSystem.resolveTypography(M3Typography.LabelMedium),
                        color = designSystem.resolveColor(accent),
                    )
                }
            }

            // The rollback's sentence, and it is the one a subscriber goes looking for: money that
            // came back is the fact the compensated row exists to state.
            component.noteText?.let {
                Text(
                    text = it,
                    style = designSystem.resolveTypography(M3Typography.BodySmall),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                )
            }
        }
    }
}

// An eSIM profile as a line the subscriber can act on.
//
// The ICCID is drawn as it arrives. This client does not group it, mask it or shorten it: those are
// three decisions about somebody's identifier, and every one of them belongs on the side that knows
// what an ICCID is.
class EsimCardRenderer : KompotComponentRenderer<EsimCardComponent> {
    @Composable
    override fun Render(
        component: EsimCardComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)
        val shape = surface.shape ?: RoundedCornerShape(20.dp)
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
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .border(1.dp, designSystem.resolveColor(M3Colors.Outline), shape)
                    .then(if (action != null) Modifier.clickable { actionHandler.handle(action) } else Modifier)
                    .padding(16.dp),
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
