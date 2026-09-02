package io.konekt.client.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.ColorToken
import io.github.youndie.kompot.KompotDesignSystem
import io.github.youndie.kompot.KompotSurface
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.SurfaceRole
import io.github.youndie.kompot.TypographyToken
import io.github.youndie.kompot.ds.material.Material3DesignSystem

// konekt's design system: Material3 for colour and typography, konekt's own answers for surfaces.
//
// Delegation and not inheritance for the first two, because they are exactly what a server theme is
// allowed to influence — `RemoteThemeDesignSystem` overlays them and repaints the Material colour
// scheme underneath, so a brand's palette arrives without a line changing here. What it must NOT
// influence is the third: the shape of a control and whether a field has a border at all are the
// client's, and a wire that cannot name them is what keeps it that way.
class KonektDesignSystem(
    private val base: KompotDesignSystem = Material3DesignSystem(),
    private val shapes: KonektShapeScale = KonektShapeScale.BrandA,
) : KompotDesignSystem {
    @Composable
    override fun resolveColor(token: ColorToken): Color = base.resolveColor(token)

    @Composable
    override fun resolveTypography(token: TypographyToken): TextStyle = base.resolveTypography(token)

    @Composable
    override fun resolveSurface(role: SurfaceRole): KompotSurface =
        when (role) {
            KompotSurfaceRoles.Button, KompotSurfaceRoles.button(PRIMARY) -> {
                KompotSurface(shape = shapes.buttonShape, minHeight = PILL_HEIGHT)
            }

            // A CANVAS FRAME NOW SAYS OTHERWISE, which is what the comment here used to be waiting
            // for. It gave a quiet button the shape and nothing else, so `quiet` and `primary` drew
            // identically — the word travelled on the wire from the day `ButtonEmphasis` existed and
            // changed nothing anybody could see. Nobody noticed because the one screen that sent it,
            // the eSIM wizard, has no primary button beside it to compare against; the orders filter
            // chips put three side by side and two of them were lying.
            //
            // Section 05 draws the unselected chip as an outline: no fill, the accent as the text,
            // a hairline border. ALL THREE ARE SET, and that is the trap the old comment named
            // correctly — Material leaves the content colour where it was, so a surface that names a
            // container and not a foreground is how "Cancel" becomes unreadable. Naming none was the
            // safe half of that; naming all three is the right whole.
            KompotSurfaceRoles.button(TONAL) -> {
                KompotSurface(
                    minHeight = PILL_HEIGHT,
                    shape = shapes.buttonShape,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    outline = Color.Transparent,
                )
            }

            KompotSurfaceRoles.button(LINK) -> {
                KompotSurface(
                    minHeight = ROW_HEIGHT,
                    shape = shapes.buttonShape,
                    container = Color.Transparent,
                    content = MaterialTheme.colorScheme.primary,
                    outline = Color.Transparent,
                )
            }

            KompotSurfaceRoles.button(DANGER) -> {
                KompotSurface(
                    minHeight = ROW_HEIGHT,
                    shape = shapes.buttonShape,
                    container = Color.Transparent,
                    content = MaterialTheme.colorScheme.error,
                    outline = Color.Transparent,
                )
            }

            KompotSurfaceRoles.button(QUIET) -> {
                KompotSurface(
                    minHeight = PILL_HEIGHT,
                    shape = shapes.buttonShape,
                    container = Color.Transparent,
                    content = MaterialTheme.colorScheme.primary,
                    outline = MaterialTheme.colorScheme.outline,
                )
            }

            // THE BORDERLESS FIELD. Transparent rather than unspecified, and the difference is the
            // whole point: unspecified means "the toolkit's default", which for an outlined field is a
            // border. Transparent is how a design that forbids borders says so.
            // OUTLINED, NOT FILLED (`B-114`, block 4): the canvas draws a field as a hairline on the
            // page with the label floating into it, and a tinted box was a chip's ground under text.
            KompotSurfaceRoles.Field -> {
                KompotSurface(
                    minHeight = FIELD_HEIGHT,
                    shape = shapes.smallShape,
                    container = Color.Transparent,
                    content = MaterialTheme.colorScheme.onSurface,
                    outline = MaterialTheme.colorScheme.outline,
                )
            }

            KompotSurfaceRoles.ReadOnlyField -> {
                KompotSurface(
                    shape = shapes.smallShape,
                    container = Color.Transparent,
                    content = MaterialTheme.colorScheme.onSurface,
                    outline = Color.Transparent,
                )
            }

            KompotSurfaceRoles.Container -> {
                KompotSurface(shape = shapes.mediumShape)
            }

            // An unfamiliar role gets the toolkit's default rather than a guess. A role is a
            // client-side key, so meeting an unknown one means the toolkit grew a hook this build
            // does not answer — and answering it wrongly is worse than not answering it.
            else -> {
                KompotSurface()
            }
        }

    internal companion object {
        const val PRIMARY = "primary"
        const val QUIET = "quiet"
        const val TONAL = "tonal"
        const val LINK = "link"
        const val DANGER = "danger"

        // THE CANVAS'S HEIGHTS (`B-114` G6), which a design system could not say until kompot#106:
        // a pill is 56 tall, a field is 56, and a control that reads as text is a 44 row.
        val PILL_HEIGHT = 56.dp
        val FIELD_HEIGHT = 56.dp
        val ROW_HEIGHT = 44.dp
    }
}
