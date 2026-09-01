package io.konekt.client.render

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// WHAT A CARD LOOKS LIKE, ONCE — because it was written six times and did not agree with itself.
//
// `B-109` was reported off a running client: the three counters inside the allowance card sit further
// in than the content of the card immediately below them. Both are cards, both are drawn a few
// pixels apart on the same screen, and they were inset by different amounts — 20 for a `surface`, 16
// for everything else — because each renderer spelled its own number.
//
// Nothing was wrong with either number on its own, which is why nothing caught it. A repeated
// coordinate does not fail; it drifts, and it is only visible where two of the copies end up side by
// side. That happened when `B-105` grouped the counters into a surface and put a roaming card under
// it.
//
// THE RADIUS LIVES HERE TOO, and for the same reason — but note what it is NOT. The canvas pairs a
// radius with an inset: 36 with 22, 22 with 18. This build draws 20, so matching the canvas properly
// means moving BOTH numbers together and re-reading every screen, which is a design pass rather than
// this fix. What this file settles is that the build agrees with ITSELF; agreeing with the canvas is
// `B-112`.
//
// A renderer that wants a different inset should say so where it draws, with a reason. What it must
// not do is write 16 again and leave the next reader to work out whether that is the card inset or a
// coincidence.
internal object CardGeometry {
    // The fallback shape, used wherever the design system supplies none. The kit's own
    // `surface.shape` still wins: a brand that rounds its cards differently must be able to.
    val Shape = RoundedCornerShape(20.dp)

    // The inset from a card's edge to its content, for every card in this client.
    //
    // SIXTEEN AND NOT TWENTY, and the choice is the smaller change rather than the better number:
    // four of the five card renderers already drew 16, so this brings the odd one into line and moves
    // nothing else on any screen. Twenty-two — what the canvas actually asks for — moves every card
    // in the build and belongs with the radius it is paired with.
    val Inset = 16.dp
}
