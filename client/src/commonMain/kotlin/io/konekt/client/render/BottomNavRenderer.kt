package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.BottomNavComponent

// The shell's bar. Four destinations, the current one marked, and the server decided all of it.
//
// LABELS AND NO ICONS, because kompot has no icon vocabulary — no wire type, no token — and an icon
// name here would be a string this client maps to a drawable it compiled in, which is a second
// dictionary kept in step by hand. The canvas draws icons; that is a gap to close in the toolkit
// rather than to paper over with a lookup table.
//
// THE SELECTED TAB IS STILL PRESSABLE, and that is not an oversight. Pressing the tab you are on is a
// refetch, which is the closest thing this application has to pull-to-refresh — and a disabled
// control that looks like the others is a worse answer to "why did nothing happen".
class BottomNavRenderer : KompotComponentRenderer<BottomNavComponent> {
    @Composable
    override fun Render(
        component: BottomNavComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(surface.shape ?: RoundedCornerShape(20.dp))
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // Evenly rather than spaced-by: four tabs share the width whatever their labels
                // measure, so a longer word in another language moves nothing but itself.
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                component.items.forEach { item ->
                    TextButton(onClick = { actionHandler.handle(item.action) }) {
                        Text(
                            text = item.label,
                            style = designSystem.resolveTypography(M3Typography.LabelLarge),
                            // The current tab is marked by COLOUR AND WEIGHT rather than by colour
                            // alone: a mark that only a hue carries is a mark a colour-blind
                            // subscriber does not get, and the brand kit is free to make the two
                            // roles close together.
                            fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Normal,
                            color =
                                designSystem.resolveColor(
                                    if (item.selected) M3Colors.Primary else M3Colors.OnSurfaceVariant,
                                ),
                        )
                    }
                }
            }
        }
    }
}
