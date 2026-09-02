package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.ScreenHeaderComponent

// THE HEADER, DRAWN: a 44-point circle in `surface_variant` with the chevron or the cross, and the
// title beside it in `title_large`. The shell draws this itself for the header it pulls out of the
// tree; this renderer is the same row for a header that reaches the registry — nested where the
// shell does not look, or on a client whose shell predates the component. One drawing, two callers.
class ScreenHeaderRenderer : KompotComponentRenderer<ScreenHeaderComponent> {
    @Composable
    override fun Render(
        component: ScreenHeaderComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        ScreenHeaderRow(
            title = component.title,
            closes = component.closes,
            onPress = component.action?.let { action -> { actionHandler.handle(action) } },
        )
    }
}

// A press with nothing to do is drawn with nothing to press: a header whose way out is the shell's
// business and which reached the registry has no shell to ask.
@Composable
fun ScreenHeaderRow(
    title: String?,
    closes: Boolean,
    onPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val designSystem = LocalKompotDesignSystem.current
    val word = if (closes) ScreenHeaderWords.CLOSE else ScreenHeaderWords.BACK

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .then(if (onPress != null) Modifier.clickable(onClick = onPress) else Modifier)
                    // Named for a screen reader and for the tests: the word is what the control
                    // does, not what it looks like.
                    .semantics { contentDescription = word },
            contentAlignment = Alignment.Center,
        ) {
            VectorIconGlyph(
                icon = if (closes) ShellGlyphs.CLOSE else ShellGlyphs.BACK,
                color = designSystem.resolveColor(M3Colors.OnSurface),
                size = 22.dp,
            )
        }
        title?.let {
            Text(
                text = it,
                style = designSystem.resolveTypography(M3Typography.TitleLarge),
                color = designSystem.resolveColor(M3Colors.OnSurface),
            )
        }
    }
}

object ScreenHeaderWords {
    const val BACK = "Back"
    const val CLOSE = "Close"
}
