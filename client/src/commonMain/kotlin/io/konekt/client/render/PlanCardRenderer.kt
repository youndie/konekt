package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.PlanCardComponent
import io.konekt.components.PlanStates
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceDensities

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
        val shape = CardGeometry.shapeOf(CardGeometry.Tier.ITEM)

        val soldOut = component.state == PlanStates.SOLD_OUT
        val loading = component.state == PlanStates.LOADING
        val action = component.action
        val registry = LocalKompotRegistry.current

        // A CARD ON THE PAGE GROUND, no outline (`B-114`, block 4): the canvas separates cards from
        // the page by ground alone — `surface` on `background` — and the hairline that used to be
        // here was a card drawn on a white page, where nothing else separated them.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(designSystem.resolveColor(M3Colors.Surface))
                    .then(
                        if (action != null && !soldOut && !loading) {
                            Modifier.clickable { actionHandler.handle(action) }
                        } else {
                            Modifier
                        },
                    ).padding(CardGeometry.Tier.ITEM.inset),
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
                Column(horizontalAlignment = Alignment.End) {
                    // THE PRICE IN THE TEXT COLOUR, not the brand's: the canvas's figure is black
                    // and bold, and a green price beside a green pill was two accents in one row.
                    Text(
                        text = if (loading) "…" else component.priceText,
                        style = designSystem.resolveTypography(M3Typography.TitleMedium),
                        color =
                            designSystem.resolveColor(
                                if (soldOut) M3Colors.OnSurfaceVariant else M3Colors.OnSurface,
                            ),
                    )
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

            component.quotaTexts
                .takeIf { it.isNotEmpty() }
                ?.let { quotas ->
                    Text(
                        text = quotas.joinToString(" · "),
                        style = designSystem.resolveTypography(M3Typography.BodyMedium),
                        color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                    )
                }

            // THE TAG AND THE PILL on one row: the tag on the left, in the chip every other chip in
            // this build is drawn by, and `Choose` on the right, in the button every other button is
            // drawn by. Both go THROUGH THE REGISTRY as synthetic nodes rather than being painted here
            // — a chip painted twice is two chips the moment one of them changes. The pill presses the
            // card's own action; a card without one, or a sold-out card, draws no pill.
            val tag = if (soldOut) component.badgeText ?: "Sold out" else component.badgeText
            val choose = component.actionText?.takeIf { action != null && !soldOut && !loading }
            if (tag != null || choose != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tag != null) {
                        registry.RenderNode(
                            component =
                                SurfaceComponent(
                                    id = "${component.id}-tag",
                                    density = SurfaceDensities.CHIP,
                                    children =
                                        listOf(
                                            TextComponent(
                                                id = "${component.id}-tag-text",
                                                text = tag,
                                                style = M3Typography.LabelMedium,
                                                color = if (soldOut) M3Colors.OnSurfaceVariant else M3Colors.Primary,
                                            ),
                                        ),
                                ),
                            actionHandler = actionHandler,
                            formController = formController,
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (choose != null && action != null) {
                        registry.RenderNode(
                            component = ButtonComponent(id = "${component.id}-choose", text = choose, action = action),
                            actionHandler = actionHandler,
                            formController = formController,
                        )
                    }
                }
            }
        }
    }
}
