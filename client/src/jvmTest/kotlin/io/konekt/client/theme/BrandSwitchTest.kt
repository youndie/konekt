package io.konekt.client.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.ColumnRenderer
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.SizeType
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
import io.github.youndie.kompot.theme.client.RemoteThemeDesignSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// B-22, drawn: **the colour kit ships from the server and the shape scale ships with the client.**
//
// A sentence like that is easy to write and easy to be wrong about, so it is tested as a CROSS
// PRODUCT rather than as a before/after pair. The same markup is rendered four times — brand A's kit
// and brand B's kit, each against brand A's radii and brand B's radii — and the claim becomes two
// separable facts:
//
//   * change the kit, hold the scale: the frame repaints and the silhouette does not move. Colour
//     travels;
//   * change the scale, hold the kit: the silhouette moves. Shape does not travel, it is compiled in.
//
// A before/after pair cannot separate those. Two frames that differ in both would satisfy "brand B
// looks different" while the radii were arriving from anywhere at all.
//
// The kits are THE FILES THE SERVER SERVES (`BrandKits`), not a fixture, so this also fails when
// somebody edits brand B's palette into brand A's — at which point the property still holds and the
// demonstration has stopped demonstrating it.
@OptIn(ExperimentalTestApi::class)
class BrandSwitchTest {
    private val brandA = BrandKits.kits().getValue("brand-a")
    private val brandB = BrandKits.kits().getValue("brand-b")

    private val registry =
        KompotRegistry(kompotCoreRenderers + kompotStandardRenderers + generatedFormsClientRenderers)

    // THE BUTTONS ARE 48dp TALL AND THAT IS THE WHOLE FIXTURE'S LOAD-BEARING DETAIL.
    //
    // Measured while writing this file, on the fixture itself: a Material button left at its default
    // height renders at 40dp, and `RoundedCornerShape` clamps a corner to half the smaller dimension,
    // so **every radius of 20dp or more draws the identical pill.** Brand B asks for 22. The sweep
    // was 8/12/16/18/19 dp differing from the pill by 392/334/252/198/165 pixels and 20/21/22/24/30 dp
    // differing by exactly zero. So the first version of this test compared brand A's pill with brand
    // B's rounded rectangle and found them pixel-identical, and its "the shape moved nothing" reading
    // was the truth about a 40dp button rather than about the shape scale.
    //
    // 48 is not a number picked to make the test pass: it is the canvas's minimum touch target, and it
    // is the first size at which the two brands are distinguishable at all (44dp still draws both as a
    // pill, 46dp differs by 182 pixels, 48dp by 238). The operator-facing consequence is in
    // docs/design/design-brand-kit.md, because somebody WILL look at a running app and report that the
    // radii did not change.
    //
    // Two further details inherited from the B-04 guard: NO BACKGROUND on the column, because the
    // alpha channel is what `compareFrames` reads as the silhouette and an opaque fill makes every
    // pixel opaque in both frames; and a `read_only_field`, whose fill and border konekt takes away
    // and the toolkit's default puts back.
    private val buttonHeightDp = 48

    private val controls =
        ColumnComponent(
            id = "controls",
            modifiers =
                listOf(
                    KompotModifierNode.Size(width = SizeType.Fill),
                    KompotModifierNode.Padding(all = 12),
                ),
            spacing = 10,
            children =
                listOf(
                    TextComponent(id = "title", text = "Add an eSIM", color = M3Colors.OnBackground),
                    ButtonComponent(
                        id = "go",
                        text = "Continue",
                        action = CloseAction,
                        modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill, heightDp = buttonHeightDp)),
                    ),
                    ButtonComponent(
                        id = "back",
                        text = "Back",
                        action = CloseAction,
                        variant = "quiet",
                        modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill, heightDp = buttonHeightDp)),
                    ),
                    ReadOnlyFieldComponent(id = "iccid", label = "ICCID", value = "8944 5000 0000 1234 567"),
                ),
        )

    // A composition root with the brand resolution TAKEN OUT and replaced by an argument. Everything
    // else is what `KonektTheme` does — which is what lets the last test below compare the two and
    // show that the real one resolves the scale it is supposed to.
    private fun renderWith(
        theme: KompotTheme,
        shapes: KonektShapeScale,
    ): ImageBitmap =
        capture {
            val base = lightColorScheme()
            // THE SAME TYPE SCALE THE APPLICATION USES (`B-114`): this path is the reference the
            // composition root is compared against pixel for pixel, so anything `KonektTheme`
            // supplies that this does not shows up as a frame that "changed size" — four points
            // taller, from titles that grew — and blames the brand switch for the type scale.
            MaterialTheme(
                colorScheme = theme.toMaterialColorScheme(base, darkMode = false),
                typography = KonektTypography.material,
            ) {
                CompositionLocalProvider(
                    LocalKonektShapeScale provides shapes,
                    LocalKompotDesignSystem provides
                        // `darkModeOverride = false`, pinned, and NOT the toolkit's
                        // `rememberKompotDesignSystem` convenience — that one leaves the override null
                        // and every token then resolves through `isSystemInDarkTheme()`, the HOST's
                        // appearance setting, while the Material scheme two lines up is pinned light.
                        // B-28 photographed the result: brand A's card came out of the DARK palette
                        // under a button from the LIGHT one. This path has to match what `KonektTheme`
                        // does, or the last test in this file compares two different clients.
                        RemoteThemeDesignSystem(
                            theme,
                            KonektDesignSystem(shapes = shapes),
                            darkModeOverride = false,
                        ),
                    LocalKompotRegistry provides registry,
                ) {
                    Controls()
                }
            }
        }

    // The real thing, brand resolution included.
    private fun renderThroughTheApplication(theme: KompotTheme): ImageBitmap =
        capture {
            KonektTheme(theme = theme, darkMode = false) {
                CompositionLocalProvider(LocalKompotRegistry provides registry) { Controls() }
            }
        }

    // The tree itself, drawn through the toolkit's own renderers rather than through Compose widgets
    // of our own — a design system is only worth testing at the point where a renderer reads it.
    @Composable
    private fun Controls() {
        ColumnRenderer().Render(
            component = controls,
            actionHandler = KompotActionHandler { },
            formController = FormController(FormSchema(formId = "b22", fields = emptyList())),
        )
    }

    private fun capture(content: @Composable () -> Unit): ImageBitmap {
        lateinit var image: ImageBitmap

        runComposeUiTest {
            setContent { content() }
            image = onRoot().captureToImage()
        }

        return image
    }

    @Test
    fun `the served kit repaints the screen and moves nothing on it`() {
        val difference =
            compareFrames(renderWith(brandA, KonektShapeScale.BrandA), renderWith(brandB, KonektShapeScale.BrandA))

        // The positive control, and this file needs it twice over: a kit that failed to apply and a
        // kit identical to the other one both produce a frame that matches, and the assertion below
        // would pass on either.
        assertTrue(difference.repainted > 0, "the two brand kits painted the same frame — nothing was switched")
        assertEquals(
            0,
            difference.moved,
            "${difference.moved} pixels changed shape when only the COLOUR kit changed — something about " +
                "appearance is travelling on the wire that should not be",
        )
    }

    @Test
    fun `the shape scale moves the screen with no wire change at all`() {
        val difference =
            compareFrames(renderWith(brandB, KonektShapeScale.BrandA), renderWith(brandB, KonektShapeScale.BrandB))

        // The counterpart of the assertion above, and the reason the pair is worth having: without
        // it, "the colour kit moved nothing" is equally satisfied by a comparison that can see no
        // movement at all. This is the same frame, the same served kit, and only the build constant
        // changed.
        assertTrue(
            difference.moved > 0,
            "brand B's radii changed no geometry — either the scale is not reaching the surfaces or the " +
                "comparison is blind, and both make the test above worthless",
        )
    }

    @Test
    fun `the silhouette follows the build and not the wire`() {
        // Stated directly rather than inferred from the two tests above. Same scale, different kits:
        // identical geometry. Same kit, different scales: different geometry. That IS the split.
        val sameScale =
            compareFrames(renderWith(brandA, KonektShapeScale.BrandB), renderWith(brandB, KonektShapeScale.BrandB))
        val sameKit =
            compareFrames(renderWith(brandA, KonektShapeScale.BrandA), renderWith(brandA, KonektShapeScale.BrandB))

        assertEquals(0, sameScale.moved, "two kits under one scale drew different silhouettes")
        assertTrue(sameKit.moved > 0, "two scales under one kit drew the same silhouette")
    }

    @Test
    fun `the composition root picks the scale the served brand names`() {
        // The one test here that exercises `KonektTheme` itself. Everything above builds its own
        // object graph and would keep passing if `forBrand` were never called by anything — which is
        // the failure mode this repository has shipped before.
        val throughTheApplication = renderThroughTheApplication(brandB)

        assertTrue(
            compareFrames(throughTheApplication, renderWith(brandB, KonektShapeScale.BrandB)).identical,
            "the application drew brand B's kit with something other than brand B's radii",
        )
        assertTrue(
            compareFrames(throughTheApplication, renderWith(brandB, KonektShapeScale.BrandA)).moved > 0,
            "the application drew brand B with brand A's radii, so the brand name is not being read",
        )

        // And the other brand, because a resolution hard-wired to `BrandB` satisfies everything above.
        assertTrue(
            compareFrames(renderThroughTheApplication(brandA), renderWith(brandA, KonektShapeScale.BrandA)).identical,
            "the application drew brand A's kit with something other than brand A's radii",
        )
    }
}
