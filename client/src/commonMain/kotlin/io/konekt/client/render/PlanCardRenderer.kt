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
import io.konekt.components.PlanCardComponent
import io.konekt.components.PlanStates

// A plan as the subscriber chooses it.
//
// EVERY STRING ARRIVES READY, including the price and every quota line. This client owns no formatter
// for money and none for gigabytes (D15), so `priceText` and `quotaTexts` are rendered as they came —
// which is also why a plan whose price moved needs no release.
//
// `state` decides whether it can be chosen, and it is an OPEN string: a word this build has never
// heard of draws the available card. The reverse default — treating an unknown state as sold out —
// would hide a plan the server is selling, which is the more expensive way to be wrong.
class PlanCardRenderer : KompotComponentRenderer<PlanCardComponent> {
    @Composable
    override fun Render(
        component: PlanCardComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)
        val shape = surface.shape ?: RoundedCornerShape(20.dp)

        val soldOut = component.state == PlanStates.SOLD_OUT
        val loading = component.state == PlanStates.LOADING
        val action = component.action

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .border(1.dp, designSystem.resolveColor(M3Colors.Outline), shape)
                    // NOT CLICKABLE WHILE SOLD OUT OR STILL BEING PRICED, and the two are refused for
                    // different reasons: one cannot be bought and the other has no price yet. A card
                    // that accepts a tap and then refuses is worse than one that does not accept it.
                    .then(
                        if (action != null && !soldOut && !loading) {
                            Modifier.clickable { actionHandler.handle(action) }
                        } else {
                            Modifier
                        },
                    ).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = component.title,
                    style = designSystem.resolveTypography(M3Typography.TitleMedium),
                    color = designSystem.resolveColor(if (soldOut) M3Colors.OnSurfaceVariant else M3Colors.OnSurface),
                )
                // THE PRICE AND WHAT A UNIT OF IT COSTS, stacked and right-aligned — which is what
                // makes the second readable AS a comparison rather than as another number on the
                // card. The canvas draws it exactly here.
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        // A plan still being priced says so where the price goes, rather than showing
                        // a stale number or an empty space. `LOADING` is on the wire precisely because
                        // only the server knows the difference.
                        text = if (loading) "…" else component.priceText,
                        style = designSystem.resolveTypography(M3Typography.TitleMedium),
                        color = designSystem.resolveColor(if (soldOut) M3Colors.OnSurfaceVariant else M3Colors.Primary),
                    )
                    // Not drawn while the price is unknown: a per-unit figure beside "…" would be a
                    // number derived from one the server has said it does not have yet.
                    if (!loading) {
                        component.perUnitText?.let {
                            Text(
                                text = it,
                                style = designSystem.resolveTypography(M3Typography.LabelSmall),
                                color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                            )
                        }
                    }
                }
            }

            component.zoneText?.let {
                Text(
                    text = it,
                    style = designSystem.resolveTypography(M3Typography.LabelMedium),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                )
            }

            // ONE LINE, JOINED, and it was one `Text` per entry. The canvas writes the quota as a
            // single subtitle — "10 GB · 30 days · 5G" — and stacking them made a card as tall as the
            // number of things a plan includes: adding minutes and messages to the home bundle turned
            // it into five lines and pushed the next card off the screen.
            //
            // The SERVER still sends them apart, which is the right way round: they are separate
            // facts, and gluing them into one string upstream would leave a client that wants a
            // column with nothing to make one from. The separator is a rendering decision and lives
            // where rendering decisions live.
            component.quotaTexts
                .takeIf { it.isNotEmpty() }
                ?.let { quotas ->
                    Text(
                        text = quotas.joinToString(" · "),
                        style = designSystem.resolveTypography(M3Typography.BodyMedium),
                        color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                    )
                }

            // The badge and the sold-out word are the same slot: a plan that is both on sale and sold
            // out has nothing to advertise.
            val note = if (soldOut) "Sold out" else component.badgeText
            note?.let {
                Text(
                    text = it,
                    style = designSystem.resolveTypography(M3Typography.LabelMedium),
                    color = designSystem.resolveColor(if (soldOut) M3Colors.Error else M3Colors.Secondary),
                )
            }
        }
    }
}
