package io.konekt.client.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
                KompotSurface(shape = shapes.buttonShape)
            }

            // Only the shape, and deliberately no container: a quiet button that named its own fill
            // would have to name its own foreground too — Material leaves the content colour where it
            // was — and a pale container with the default foreground is how "Cancel" becomes
            // unreadable. Emphasis is drawn by the renderer's own colours until a canvas frame says
            // otherwise.
            KompotSurfaceRoles.button(QUIET) -> {
                KompotSurface(shape = shapes.buttonShape)
            }

            // THE BORDERLESS FIELD. Transparent rather than unspecified, and the difference is the
            // whole point: unspecified means "the toolkit's default", which for an outlined field is a
            // border. Transparent is how a design that forbids borders says so.
            KompotSurfaceRoles.Field -> {
                KompotSurface(
                    shape = shapes.smallShape,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    // Set because the container is. A container without a content colour leaves
                    // Material's own foreground on top of it, which is a trap laid for whoever sets
                    // one half.
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    outline = Color.Transparent,
                )
            }

            // A value, not an input. Drawn as a filled field it says the opposite of what it is, so it
            // keeps the shape and gives up both the fill and the border.
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

    private companion object {
        const val PRIMARY = "primary"
        const val QUIET = "quiet"
    }
}
