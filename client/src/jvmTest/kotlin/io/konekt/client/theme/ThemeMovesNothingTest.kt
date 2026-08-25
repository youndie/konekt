package io.konekt.client.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import io.github.youndie.kompot.ColorToken
import io.github.youndie.kompot.ColumnRenderer
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotDesignSystem
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.SizeType
import io.github.youndie.kompot.TypographyToken
import io.github.youndie.kompot.ds.material.toMaterialColorScheme
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.forms.ReadOnlyFieldComponent
import io.github.youndie.kompot.generated.generatedFormsClientRenderers
import io.github.youndie.kompot.kompotCoreRenderers
import io.github.youndie.kompot.kompotStandardRenderers
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.CloseAction
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.theme.KompotTheme
import io.github.youndie.kompot.theme.client.rememberKompotDesignSystem
import io.github.youndie.kompot.theme.kompotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The other half of B-04, drawn rather than asked.
//
// The unit guard beside this one reads the design system's answers; this one renders the toolkit's
// real renderers with them and compares the two frames — before the theme arrives and after. The
// property is the one the canvas cares about: **a brand kit repaints a screen and moves nothing.**
//
// IT IS NOT A GOLDEN PAIR, and that is deliberate rather than a shortcut. A committed pair of PNGs
// compares this machine against a recording of this machine, needs a bundled font to travel, and —
// since goldens are not wired into `check` — proves nothing at all until somebody runs the task that
// reads them. Comparing the two frames WITHIN one run needs no golden, no font and no separate task:
// it runs in `build`, on every machine, and it fails for the reason the item exists.
//
// What it cannot catch is a change to konekt's own surfaces — both frames would move together. That
// is the golden pair's job and it belongs to B-28, which brings the screenshot harness.
@OptIn(ExperimentalTestApi::class)
class ThemeMovesNothingTest {
    private val brandB =
        kompotTheme("brand-b") {
            light {
                color(M3Colors.Primary, "#FF00695C")
                color(M3Colors.OnPrimary, "#FFFFFFFF")
                color(M3Colors.Surface, "#FFF3F6F5")
                color(M3Colors.OnSurface, "#FF12211E")
                color(M3Colors.Background, "#FFF3F6F5")
                color(M3Colors.OnBackground, "#FF12211E")
                color(M3Colors.SurfaceVariant, "#FFDCE5E3")
                color(M3Colors.OnSurfaceVariant, "#FF12211E")
            }
        }

    private val registry =
        KompotRegistry(kompotCoreRenderers + kompotStandardRenderers + generatedFormsClientRenderers)

    private val controls =
        ColumnComponent(
            id = "controls",
            // NO BACKGROUND, and that is load-bearing rather than a simplification. The
            // comparison below reads the alpha channel as the silhouette — a pixel is either painted
            // or it is not — and painting an opaque background first makes every pixel opaque in both
            // frames. The first version of this fixture had one, the geometry check was vacuous, and
            // the control test below is what said so.
            modifiers =
                listOf(
                    KompotModifierNode.Size(width = SizeType.Fill),
                    KompotModifierNode.Padding(all = 12),
                ),
            spacing = 10,
            children =
                listOf(
                    // A token-coloured word, so the theme reaches the screen through resolveColor as
                    // well as through the Material scheme. Without it a "nothing changed" result
                    // would be ambiguous between a theme that did nothing and a theme that did
                    // nothing visible.
                    TextComponent(id = "title", text = "Add an eSIM", color = M3Colors.OnBackground),
                    ButtonComponent(id = "go", text = "Continue", action = CloseAction),
                    ButtonComponent(id = "back", text = "Back", action = CloseAction, variant = "quiet"),
                    // THE DISCRIMINATING ROW. konekt draws a read-only field with no fill and no
                    // border, while the toolkit's default for that role is an outlined box — so a
                    // design system whose surfaces are dropped grows a rectangle here. The two
                    // buttons above cannot show that: Material's own button shape is already a pill,
                    // which is exactly what brand A asks for, and the first version of this test
                    // passed its comparison and proved nothing because of it.
                    ReadOnlyFieldComponent(id = "iccid", label = "ICCID", value = "8944 5000 0000 1234 567"),
                ),
        )

    // The pre-0.31 RemoteThemeDesignSystem, reproduced. It forwards colour and typography and
    // answers the interface default for surfaces — which is what "inherits the default" looked like
    // from the outside, and what this file's comparison has to be able to see.
    private class SurfacelessOverlay(
        private val wrapped: KompotDesignSystem,
    ) : KompotDesignSystem {
        @Composable
        override fun resolveColor(token: ColorToken): Color = wrapped.resolveColor(token)

        @Composable
        override fun resolveTypography(token: TypographyToken): TextStyle = wrapped.resolveTypography(token)
        // resolveSurface deliberately not overridden: that IS the defect.
    }

    private fun render(
        theme: KompotTheme?,
        dropSurfaces: Boolean = false,
    ): ImageBitmap {
        lateinit var image: ImageBitmap

        runComposeUiTest {
            setContent {
                val base = lightColorScheme()
                // Exactly what a composition root does, both halves of it: the Material scheme is
                // repainted from the theme, and the design system is wrapped in it. Testing only the
                // second would leave the button's own colours untouched and the comparison vacuous.
                val scheme = theme?.toMaterialColorScheme(base, darkMode = false) ?: base

                MaterialTheme(colorScheme = scheme) {
                    CompositionLocalProvider(
                        LocalKompotDesignSystem provides
                            rememberKompotDesignSystem(theme, KonektDesignSystem())
                                .let { if (dropSurfaces) SurfacelessOverlay(it) else it },
                        LocalKompotRegistry provides registry,
                    ) {
                        ColumnRenderer().Render(
                            component = controls,
                            actionHandler = KompotActionHandler { },
                            formController = FormController(FormSchema(formId = "b04", fields = emptyList())),
                        )
                    }
                }
            }

            image = onRoot().captureToImage()
        }

        return image
    }

    private data class Difference(
        val repainted: Int,
        val moved: Int,
    )

    // ALPHA IS THE GEOMETRY and colour is everything else. A pill becoming a rounded rectangle, a
    // border appearing, a control changing size — every one of those changes which pixels are
    // covered. Repainting a covered pixel does not.
    private fun compare(
        before: ImageBitmap,
        after: ImageBitmap,
    ): Difference {
        assertEquals(before.width to before.height, after.width to after.height, "the frame changed size")

        val plain = before.toPixelMap()
        val themed = after.toPixelMap()
        var repainted = 0
        var moved = 0

        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                val a = plain[x, y]
                val b = themed[x, y]
                if (a.alpha != b.alpha) moved++
                if (a != b) repainted++
            }
        }

        return Difference(repainted = repainted, moved = moved)
    }

    @Test
    fun `a brand kit repaints the screen and moves nothing on it`() {
        val difference = compare(render(theme = null), render(theme = brandB))

        // The positive control first, and it is not a formality: if the theme silently failed to
        // apply, every pixel would match and the assertion below would pass while proving nothing.
        // This is the same trap the unit guard beside this one closes with assertNotSame.
        assertTrue(difference.repainted > 0, "the theme changed no pixel at all — it was not applied")

        assertEquals(
            0,
            difference.moved,
            "\${difference.moved} pixels changed shape, so the theme answered for more than colour",
        )
    }

    @Test
    fun `the comparison can see the defect it exists to guard against`() {
        // The second positive control, and the one that makes the first assertion mean something. A
        // pixel comparison that reports "nothing moved" is only evidence if it would report movement
        // when a surface genuinely goes missing — and this is the exact way it used to go missing.
        val difference = compare(render(theme = null), render(theme = brandB, dropSurfaces = true))

        assertTrue(
            difference.moved > 0,
            "an overlay that drops every surface changed no geometry — the comparison is blind",
        )
    }
}
