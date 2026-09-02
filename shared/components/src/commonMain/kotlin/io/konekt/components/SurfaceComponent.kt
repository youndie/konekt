package io.konekt.components

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A GROUP ON A GROUND, and it is the eleventh name here for one missing property rather than for a
// product concept.
//
// The canvas draws three of these — the balance block, the plan card the counters sit inside, and the
// roaming row — as filled, rounded containers. What the served tree had instead was four siblings in
// the screen's own column: not a card drawn wrongly, no card at all.
//
// WHAT THE TOOLKIT ALREADY HAS, because the first version of this item was wrong about it.
// `KompotModifierNode` carries `Background(color)`, `Padding`, `Size`, `Weight` and `Gradient`, and
// the client resolves a `Background` through the design system — so the server CAN already say "this
// column stands on primary_container", and a brand already repaints it. Read in
// `kompot-core:0.33.1.91` and in `KompotClientKt`'s modifier chain, not inferred.
//
// WHAT IS MISSING IS THE CORNER, and only that. The chain applies
// `Modifier.background(color, shape = null)` — a rectangle — and there is no shape modifier on the
// wire, deliberately: radii are a client build constant (research §1.2, D2), which is what lets
// brand B change `lg` 36→22 without a server release. So the shape has to be chosen by the CLIENT,
// and a component is the only place this build can put that choice.
//
// SO THIS IS A WORKAROUND WITH A NAME. `U14` asks kompot to round a `Background` from the design
// system's own surface shape, which would make this type deletable: a `column` with a `Background`
// would then be exactly what this is. Until then, `konekt.surface` — and the day U14 lands, this file
// and its renderer go, and the server sends a column.
@Serializable
@SerialName("surface")
@KompotComponentMarker
data class SurfaceComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val children: List<KompotComponent>,
    // WHICH GROUND, as a role rather than a colour. The same bargain every open string here makes:
    // an unrecognised word draws the neutral form rather than failing, and the colour itself is the
    // served brand kit's to decide — which is the whole claim this product is for.
    val tone: String = SurfaceTones.NEUTRAL,
    val spacing: Int = 12,
    // THREE MORE FACTS ABOUT A GROUP ON A GROUND, added by `B-114` for the plan detail and the
    // purchase result, and each is a property of the CONTAINER rather than a new kind of thing:
    //
    //   `density` — `card` is the headline block the canvas draws at `lg`; `chip` is the small
    //   pill it draws attributes in (`30 days`, `Hotspot allowed`): `sm` radius, tight inset,
    //   wraps its content. One type, because a chip is a card that is small, not a different noun.
    //
    //   `dividers` — a hairline between children. What turns a column of label/value rows into the
    //   spec table the canvas draws, and the receipt under a paid order.
    //
    //   `pinned` — the frame keeps this one at the bottom, above the bar and outside the scroll.
    //   The buy button on a plan lives there in the canvas with `Charged once` on the line above
    //   it; a screen that scrolls must not scroll its one action away. One per screen; a second is
    //   drawn in place like any other surface, because a frame with two footers is a frame with a
    //   bug, and drawing the second is how somebody notices.
    val density: String = SurfaceDensities.CARD,
    val dividers: Boolean = false,
    val pinned: Boolean = false,
) : KompotComponent

object SurfaceDensities {
    const val CARD = "card"
    const val CHIP = "chip"
}
