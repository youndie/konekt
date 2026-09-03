package io.konekt.screenshots

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.SizeType
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.CloseAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import ru.workinprogress.viddik.annotations.ViddikScreenshot

// The same markup in both brands, which is section 08 of the canvas and the assertion it exists for:
// nothing in the layout depends on the shape scale, so a brand's radii can be a client build constant
// (D2) instead of a token on the wire.
//
// TWO CONTROLS, AND BOTH ARE CHOSEN RATHER THAN CONVENIENT:
//
//   * the buttons are **48dp tall**. `RoundedCornerShape` clamps a corner to half the smaller
//     dimension, so on a Material button at its default 40dp height every radius of 20dp or more
//     draws the identical pill — and brand B asks for 22. B-22 measured the sweep: 44dp differs by 0
//     pixels, 46dp by 182, 48dp by 238. 48 is also the canvas's minimum touch target, so it is the
//     first ORDINARY size that can tell the two brands apart at all;
//   * the counter card, whose container reads `mediumShape` — 20dp against 12dp, far below the clamp.
//     It is the control that keeps this pair honest if a future brand stops using pills, and it is
//     the one `lg` cannot reach (see `GoldenContentTest`, which measures exactly that).
//
// `darkVariant = true` doubles each into a light and a dark frame, which is how the canvas draws every
// section. The dark half is not decoration here: `KonektTheme` builds the Material scheme from the
// kit's dark palette AND resolves every `ColorToken` through the same kit, and a frame is the only
// thing that notices when those two disagree.

private const val FRAME_WIDTH = 360
private const val FRAME_HEIGHT = 320

// 48, not the default 40 — see above. Written as a constant so that changing it is a visible change.
private const val TOUCH_TARGET_DP = 48

@Composable
private fun BrandShowcase(brand: String) {
    BrandFrame(brand) {
        Tree(brandShowcaseComponents())
    }
}

// The markup itself, as values rather than inline in the composable. The studio pilot renders the
// same list through the toolkit's dispatch from a wire body, and comparing its frame with these
// goldens only means anything if both sides draw one list.
fun brandShowcaseComponents(): List<KompotComponent> =
    listOf(
        TextComponent(id = "title", text = "Your plan", color = M3Colors.OnBackground),
        UsageCounterCardComponent(
            id = "counter",
            modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
            title = "Data",
            valueText = "15.8 GB left",
            progress = 0.38f,
            state = CounterStates.NORMAL,
        ),
        ButtonComponent(
            id = "buy",
            text = "Add data",
            action = CloseAction,
            modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill, heightDp = TOUCH_TARGET_DP)),
        ),
        ButtonComponent(
            id = "back",
            text = "Not now",
            action = CloseAction,
            variant = "quiet",
            modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill, heightDp = TOUCH_TARGET_DP)),
        ),
    )

@Composable
@ViddikScreenshot(
    name = "A",
    group = "Brand",
    width = FRAME_WIDTH,
    height = FRAME_HEIGHT,
    darkVariant = true,
)
fun BrandA() {
    BrandShowcase("brand-a")
}

@Composable
@ViddikScreenshot(
    name = "B",
    group = "Brand",
    width = FRAME_WIDTH,
    height = FRAME_HEIGHT,
    darkVariant = true,
)
fun BrandB() {
    BrandShowcase("brand-b")
}
