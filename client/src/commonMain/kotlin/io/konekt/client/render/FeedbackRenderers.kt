package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.konekt.components.MessageTones
import io.konekt.components.SkeletonComponent
import io.konekt.components.SkeletonShapes
import io.konekt.components.SnackbarComponent
import io.konekt.components.StepMeterComponent

// A message that is part of the layout is a `banner`; this one is not.
//
// SNACKBAR AND BANNER ARE SEPARATE WIRE TYPES because the difference is LIFETIME rather than
// appearance — the component's own comment says so. This build has no host for a transient message,
// so it draws it in place and says what that costs: a snackbar drawn in the tree stays until the tree
// changes, which is longer than a snackbar should live and shorter than never being shown at all.
class SnackbarRenderer : KompotComponentRenderer<SnackbarComponent> {
    @Composable
    override fun Render(
        component: SnackbarComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val shape = designSystem.resolveSurface(KompotSurfaceRoles.Container).shape ?: RoundedCornerShape(12.dp)

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(designSystem.resolveColor(M3Colors.OnSurface))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = component.text,
                style = designSystem.resolveTypography(M3Typography.BodyMedium),
                // Inverted on purpose: a snackbar sits ON the surface rather than in it, which is the
                // one place this design system deliberately reverses its colour roles.
                color = designSystem.resolveColor(M3Colors.Surface),
            )

            val label = component.actionText
            val action = component.action
            if (label != null && action != null) {
                TextButton(onClick = { actionHandler.handle(action) }) {
                    Text(
                        text = label,
                        style = designSystem.resolveTypography(M3Typography.LabelLarge),
                        color =
                            designSystem.resolveColor(
                                if (component.tone == MessageTones.ERROR) M3Colors.Error else M3Colors.Primary,
                            ),
                    )
                }
            }
        }
    }
}

// "Step 2 of 4", and the wizard is the only thing that sends one.
//
// DRAWN AS DOTS RATHER THAN AS A BAR, because the number of steps is small and known: a bar says how
// far along and a dot row says WHICH step, and a subscriber halfway through an eSIM install is asking
// the second question.
class StepMeterRenderer : KompotComponentRenderer<StepMeterComponent> {
    @Composable
    override fun Render(
        component: StepMeterComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            component.label?.let {
                Text(
                    text = it,
                    style = designSystem.resolveTypography(M3Typography.LabelMedium),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // ONE-BASED, the way it is spoken and the way the component documents itself. An
                // off-by-one here is a wizard that says it is on step one while showing step two, and
                // nothing about the screen looks wrong.
                repeat(component.total.coerceAtLeast(0)) { index ->
                    val done = index < component.current
                    Row(
                        modifier =
                            Modifier
                                .height(6.dp)
                                .width(if (done) 24.dp else 12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    designSystem.resolveColor(if (done) M3Colors.Primary else M3Colors.Outline),
                                ),
                    ) {}
                }
            }
        }
    }
}

// What stands where a real component is still loading.
//
// IT IS ON THE WIRE RATHER THAN A CLIENT FLAG because only the server knows a list is being fetched
// rather than genuinely empty, and those two look identical drawn as nothing. So this draws SOMETHING
// — a shape of about the right size — and never nothing.
class SkeletonRenderer : KompotComponentRenderer<SkeletonComponent> {
    @Composable
    override fun Render(
        component: SkeletonComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val height =
            when (component.shape) {
                SkeletonShapes.CARD -> 96.dp

                SkeletonShapes.ROW -> 48.dp

                // An unknown shape draws the thinnest of the three rather than nothing: a placeholder
                // that guesses small is a layout that shifts a little, and one that draws nothing is a
                // screen that looks loaded and is not.
                else -> 16.dp
            }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(component.count.coerceAtLeast(1)) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(height)
                            .clip(RoundedCornerShape(8.dp))
                            .background(designSystem.resolveColor(M3Colors.SurfaceVariant)),
                ) {}
            }
        }
    }
}
