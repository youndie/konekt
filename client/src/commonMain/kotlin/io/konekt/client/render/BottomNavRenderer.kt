package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.BottomNavComponent

// The shell's bar. Four destinations, the current one marked, and the server decided all of it.
//
// THE ICON ARRIVES AS A SHAPE, not as a name (`B-110`). kompot still has no icon vocabulary; what
// changed is the answer to that. A name plus a table here would be a second dictionary kept in step
// by hand, failing silently — an unknown name draws nothing, and a tab with no icon looks like a tab
// whose icon has not loaded. So the server sends path data and this draws it, the same arrangement
// `EsimQrRenderer` already uses for a QR.
//
// THE COLOUR IS DECIDED HERE and never sent. It is the same role the label takes, so the two cannot
// disagree, and a brand kit can still repaint the bar — which a server-sent hex would have made the
// one thing a rebrand could not reach.
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
        val rule = designSystem.resolveColor(M3Colors.OutlineVariant)
        val pill = designSystem.resolveColor(M3Colors.PrimaryContainer)

        // ON THE PAGE'S GROUND WITH A RULE ABOVE IT, edge to edge (`B-114` G3). It used to be a
        // rounded tinted slab, which is a card's costume on the window's furniture; the canvas draws
        // the bar in the page colour, separated by one hairline in `outline_variant`.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(designSystem.resolveColor(M3Colors.Background))
                    .drawBehind {
                        drawLine(
                            color = rule,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }.padding(top = 8.dp, bottom = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // Evenly rather than spaced-by: four tabs share the width whatever their labels
                // measure, so a longer word in another language moves nothing but itself.
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                component.items.forEach { item ->
                    TextButton(onClick = { actionHandler.handle(item.action) }) {
                        val ink =
                            designSystem.resolveColor(
                                if (item.selected) M3Colors.Primary else M3Colors.OnSurfaceVariant,
                            )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // THE CURRENT TAB SITS IN A TONAL PILL, which is how the canvas marks
                            // it — colour and weight on the label stay, because a mark only a hue
                            // carries is one a colour-blind subscriber does not get, but the pill
                            // is what makes the bar read as a bar and not as four words.
                            Box(
                                modifier =
                                    Modifier
                                        .size(width = 56.dp, height = 32.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(if (item.selected) pill else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                item.icon?.let { VectorIconGlyph(it, ink) }
                            }
                            Text(
                                text = item.label,
                                style = designSystem.resolveTypography(M3Typography.LabelLarge),
                                // The current tab is marked by COLOUR AND WEIGHT rather than by colour
                                // alone: a mark that only a hue carries is a mark a colour-blind
                                // subscriber does not get, and the brand kit is free to make the two
                                // roles close together.
                                fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Normal,
                                color = ink,
                            )
                        }
                    }
                }
            }
        }
    }
}
