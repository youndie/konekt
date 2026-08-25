package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.ColorToken
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent

// The card the subscriber opens the application to look at.
//
// EVERY STRING ON IT ARRIVES READY. The title, the value and the caption are built on the server and
// rendered as they came — no number is divided by a thousand here, no unit is chosen here, and no
// sentence is assembled here. That is the backend-driven bargain taken deliberately: a client that
// cannot format cannot format inconsistently, and there is no second copy of the formatter waiting
// in a view model.
//
// `progress` is the exception and has to be, because it is geometry rather than language.
class UsageCounterCardRenderer : KompotComponentRenderer<UsageCounterCardComponent> {
    @Composable
    override fun Render(
        component: UsageCounterCardComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current

        // The container's shape comes from the design system, not from this file. A brand's radii are
        // a client build constant (research §1.2) and the constant lives in one place; a renderer
        // that rounded its own corners would be a second shape scale nobody could find.
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)
        val accent = designSystem.resolveColor(component.state.accentToken())

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(surface.shape ?: RoundedCornerShape(20.dp))
                    .background(designSystem.resolveColor(M3Colors.SurfaceVariant))
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = component.title,
                style = designSystem.resolveTypography(M3Typography.LabelMedium),
                color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
            )
            Text(
                text = component.valueText,
                style = designSystem.resolveTypography(M3Typography.HeadlineMedium),
                color = accent,
            )

            component.progress?.let { fraction ->
                LinearProgressIndicator(
                    // A lambda and not a value: the overload taking a plain Float is the deprecated
                    // one, and the difference is whether the bar animates when the number changes —
                    // which is the whole point of a counter that moves live.
                    progress = { fraction.coerceIn(0f, 1f) },
                    color = accent,
                    trackColor = designSystem.resolveColor(M3Colors.Outline),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            component.captionText?.let { caption ->
                Text(
                    text = caption,
                    style = designSystem.resolveTypography(M3Typography.BodySmall),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                )
            }
        }
    }
}

// A colour ROLE per state, never a colour. The server names `low` and the design system decides what
// that looks like, which is what lets a brand kit repaint this card without a client release.
//
// The canvas draws the low state in amber, and Material 3 has no amber role — `secondary` is the
// nearest, and a brand that wants amber sets `secondary` to amber. That is the mechanism working
// rather than a compromise: the alternative is a colour literal here, which is precisely the thing a
// token exists to prevent.
private fun String.accentToken(): ColorToken =
    when (this) {
        CounterStates.EXHAUSTED -> M3Colors.Error

        CounterStates.LOW -> M3Colors.Secondary

        // An unrecognised word draws the ORDINARY card. Not nothing, and not an error colour: a
        // state this build has never heard of is a state it must not editorialise about.
        else -> M3Colors.Primary
    }
