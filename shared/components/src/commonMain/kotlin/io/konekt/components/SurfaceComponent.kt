package io.konekt.components

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A GROUP ON A GROUND — a card, a chip, a table, a footer — said as words on one container rather
// than as four nouns.
//
// The canvas draws the balance block, the plan card the counters sit inside, and the roaming row as
// filled, rounded containers. What the served tree had at first was four siblings in the screen's
// own column: not a card drawn wrongly, no card at all.
//
// WHAT THE TOOLKIT HAS. `KompotModifierNode` carries `Background(color)`, `Padding`, `Size`,
// `Weight` and `Gradient`, and the client resolves a `Background` through the design system — so
// the server could always say "this column stands on primary_container", and a brand repaints it.
// What it could not say until kompot `0.34` was the CORNER: the chain applied
// `Modifier.background(color, shape = null)`, a rectangle, and there is no shape on the wire on
// purpose — radii are a client build constant (research §1.2, D2), which is what lets brand B change
// `lg` 36→22 without a server release. This type began as the one place a client could choose that
// shape: a workaround with a name, filed as U14 (kompot#95).
//
// U14 CLOSED IN kompot#96, and the corner is the toolkit's now: `background` takes an optional
// `role`, and a column whose background names `container` is clipped and painted to the design
// system's shape for that role. So the reason this type was written is gone. The type is not,
// because by then it had become the container the canvas actually needs — `B-114` gave it a
// `density` (a chip is a card that is small), `dividers` (what makes a column of rows a table) and
// `pinned` (the frame keeps it above the bar, outside the scroll) — and kompot `0.36` has no word
// for any of the three: no divider, no chip, no footer slot. Those are konekt's words, priced in
// operator-boundaries; the corner alone would today be a `column` with a `background(role)`.
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
