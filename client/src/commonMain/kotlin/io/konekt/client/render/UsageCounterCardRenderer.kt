package io.konekt.client.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

        // INLINE IS A ROW IN SOMEBODY ELSE'S CARD, and the difference is the chrome and the way the
        // label and the value sit — not the content. The canvas draws the grouped allowances as
        // label and figure on ONE baseline with a fat bar under them; a card stacks them and stands
        // them on a ground of their own, which inside another card is two grounds and a double
        // padding.
        Column(
            modifier =
                if (component.inline) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .clip(CardGeometry.shapeOf(CardGeometry.Tier.CARD))
                        .background(designSystem.resolveColor(M3Colors.Surface))
                        .padding(CardGeometry.Tier.CARD.inset)
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (component.inline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = component.title,
                        style = designSystem.resolveTypography(M3Typography.BodyMedium),
                        color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                    )
                    Text(
                        text = component.valueText,
                        style = designSystem.resolveTypography(M3Typography.TitleMedium),
                        color = accent,
                    )
                }
            } else {
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
            }

            component.progress?.let { fraction ->
                LinearProgressIndicator(
                    // A lambda and not a value: the overload taking a plain Float is the deprecated
                    // one, and the difference is whether the bar animates when the number changes —
                    // which is the whole point of a counter that moves live.
                    progress = { fraction.coerceIn(0f, 1f) },
                    color = accent,
                    // THE REMAINDER IS A TINT OF THE ACCENT, not the outline grey: the canvas draws
                    // what is left in `primary_container`, and the low and exhausted rows tint their
                    // remainder in their own accent's container. Outline read as a rule, not as a bar.
                    trackColor = designSystem.resolveColor(component.state.trackToken()),
                    // A GAP AND NO STOP DOT (`B-114` G7). Material's indicator draws a dot at the end
                    // of the track and butts the two segments together; the canvas's bar is two
                    // pills with five points of air between them. The dot was the single most visible
                    // thing wrong on the home screen.
                    gapSize = 5.dp,
                    drawStopIndicator = {},
                    // TWELVE POINTS TALL WHEN IT IS A ROW, which is what the canvas draws and what the
                    // complaint was about: the default indicator is a hairline, and a hairline under a
                    // number reads as decoration rather than as the quantity it represents.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (component.inline) Modifier.height(12.dp) else Modifier),
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

        // BOUGHT AND NOT COUNTING, drawn in the MUTED role rather than the accent one.
        //
        // The bar is full and will still be full in a month, so drawing it in the primary colour
        // says "running, plenty left" — which is the one thing a dormant package is not. Quiet is
        // exactly the claim: nothing is happening here yet.
        //
        // `onSurfaceVariant` AND NOT `outline`, though outline is quieter. Outline is the design
        // system's role for BORDERS, and this colour draws a number somebody has to read — reaching
        // for a token because its default value looks right is how `sold_out` ended up labelling a
        // subscriber's own tariff (`B-86`). A muted-content role is what this is.
        //
        // This branch is the whole of `B-88`'s second criterion. Before it, the server had said
        // `dormant` since `B-19` and this `when` had no arm for it, so the state the roaming feature
        // exists to show fell through to the ordinary card — correct degradation, and the wrong
        // answer for the one word that was not a stranger.
        CounterStates.DORMANT -> M3Colors.OnSurfaceVariant

        // An unrecognised word draws the ORDINARY card. Not nothing, and not an error colour: a
        // state this build has never heard of is a state it must not editorialise about.
        else -> M3Colors.Primary
    }

// THE REMAINDER OF THE BAR IN THE STATE'S OWN CONTAINER (`B-114` G7). The canvas draws what is left
// in `primary_container` on an ordinary row, in the peach container on a low one and in the pink on
// an exhausted one — so the whole bar changes colour with the state, not only the filled part. One
// mint track under a red figure is what a single track colour produced.
fun String.trackToken(): ColorToken =
    when (this) {
        CounterStates.EXHAUSTED -> M3Colors.ErrorContainer

        CounterStates.LOW -> M3Colors.SecondaryContainer

        // BOUGHT AND NOT COUNTING, drawn in the MUTED role rather than the accent one.
        //
        // The bar is full and will still be full in a month, so drawing it in the primary colour
        // says "running, plenty left" — which is the one thing a dormant package is not. Quiet is
        // exactly the claim: nothing is happening here yet.
        //
        // `onSurfaceVariant` AND NOT `outline`, though outline is quieter. Outline is the design
        // system's role for BORDERS, and this colour draws a number somebody has to read — reaching
        // for a token because its default value looks right is how `sold_out` ended up labelling a
        // subscriber's own tariff (`B-86`). A muted-content role is what this is.
        //
        // This branch is the whole of `B-88`'s second criterion. Before it, the server had said
        // `dormant` since `B-19` and this `when` had no arm for it, so the state the roaming feature
        // exists to show fell through to the ordinary card — correct degradation, and the wrong
        // answer for the one word that was not a stranger.
        CounterStates.DORMANT -> M3Colors.OnSurfaceVariant

        // An unrecognised word draws the ORDINARY card. Not nothing, and not an error colour: a
        // state this build has never heard of is a state it must not editorialise about.
        else -> M3Colors.PrimaryContainer
    }
