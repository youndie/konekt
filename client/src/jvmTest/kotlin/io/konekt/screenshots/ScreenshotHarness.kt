package io.konekt.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.youndie.kompot.ColumnRenderer
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.client.render.konektRegistry
import io.konekt.client.theme.BrandKits
import io.konekt.client.theme.KonektTheme
import io.konekt.client.theme.KonektTypography
import ru.workinprogress.viddik.LocalViddikDarkTheme
import ru.workinprogress.viddik.core.viddikTypography

// What every golden in this package is a photograph OF, in one place.
//
// The subject is **the application's own composition root** — `KonektTheme` resolving a served brand
// kit, and `konektRegistry()` deciding which renderer draws what. A fixture that assembled a design
// system by hand would photograph a second client that nobody ships; this repository has already been
// bitten by tests that build their own object graph and pass while the application does not assemble.
//
// TWO THINGS ARE FORCED HERE AND BOTH ARE ABOUT THE GOLDENS BEING PORTABLE:
//
//   * `viddikTypography()` — the bundled Roboto with rasterization pinned. Without it every glyph is
//     drawn by whatever the host machine has installed, and a golden recorded on the Mac fails on the
//     Linux box for a reason that looks like a layout change. It reaches konekt's renderers because
//     `Material3DesignSystem.resolveTypography` reads `MaterialTheme.typography`, and `KonektTheme`'s
//     inner `MaterialTheme(colorScheme = …)` inherits `typography` from this outer one;
//   * no opaque background. The alpha channel is what tells a shape change from a colour change —
//     see `GoldenContentTest` — and a filled root makes every pixel opaque in every frame.
//
// The kits are the files the server actually ships (`BrandKits`), not a palette retyped here: a
// fixture with its own copy of brand B agrees with itself while the served file drifts.
@Composable
fun BrandFrame(
    brand: String,
    content: @Composable () -> Unit,
) {
    // `LocalViddikDarkTheme` is how viddik asks for the second frame of a `darkVariant = true` case.
    // The fixture has to read it: viddik cannot know which of konekt's own switches means "dark".
    val darkMode = LocalViddikDarkTheme.current

    // THE PRODUCT'S SCALE IN VIDDIK'S FAMILY: every size and weight `KonektTypography` decides, on
    // the Roboto viddik pins so a golden is the same pixels on a Mac and on the Linux runner. What
    // a golden therefore does NOT photograph is the typeface itself — Manrope and Space Grotesk are
    // shipped, static and unhinted, and still drift by 50–80 glyph-edge pixels between the two
    // platforms (`B-114` G1). The face is checked by eye against the canvas; the layout, by this.
    KonektTheme(
        theme = BrandKits.kits().getValue(brand),
        darkMode = darkMode,
        typography = viddikTypography(KonektTypography.material),
    ) {
        run {
            CompositionLocalProvider(LocalKompotRegistry provides konektRegistry()) {
                content()
            }
        }
    }
}

// The tree drawn through the toolkit's own column renderer rather than through Compose widgets of
// our own — the same reason `BrandSwitchTest` does it: a design system is only worth photographing at
// the point where a renderer reads it.
@Composable
fun Tree(children: List<KompotComponent>) {
    ColumnRenderer().Render(
        component = ColumnComponent(id = "frame", spacing = 12, children = children),
        actionHandler = KompotActionHandler { },
        formController = FormController(FormSchema(formId = "screenshots", fields = emptyList())),
    )
}

// The brand the counter frames are drawn in. They exist to photograph COPY and COLOUR ROLE per state,
// so they hold the brand constant; the brand pair is the other file.
const val DEFAULT_BRAND = "brand-a"
