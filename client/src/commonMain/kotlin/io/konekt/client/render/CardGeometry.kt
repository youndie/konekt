package io.konekt.client.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.konekt.client.theme.LocalKonektShapeScale

// WHAT A CARD LOOKS LIKE, ONCE — because it was written six times and did not agree with itself.
//
// `B-109` was reported off a running client: the three counters inside the allowance card sat further
// in than the content of the card immediately below them. Both are cards, both are drawn a few pixels
// apart on the same screen, and they were inset by different amounts — 20 for a `surface`, 16 for
// everything else — because each renderer spelled its own number. Nothing was wrong with either on its
// own, which is why nothing caught it: a repeated coordinate does not fail, it drifts, and it is only
// visible where two of the copies end up side by side.
//
// AND THEN `B-112`: agreeing with ITSELF is not the same as agreeing with the design.
//
// The canvas pairs a corner radius with an inset, and the pairing is a SCALE — the bigger the block,
// the rounder and the roomier. Read out of `docs/design/konekt-esim-app.dc.html` by counting every
// `border-radius:R;padding:P` and looking at what sits inside it:
//
//     36 / 22   ten times   Balance, Smart 20, Data remaining, Data plan — a screen's headline blocks
//     22 / 18   ten times   Turkey, Georgia, Serbia, an order row — the items in a list
//     22 / 16   five times  Reversed, Installing on this phone? — notices
//     20 / 14   seven times eSIM ready to install, Card ···4417 — compact rows
//
// This build drew radius 20 with inset 16 for all six of them, which is not a pair the canvas uses
// anywhere. THREE TIERS AND NOT ONE, because flattening a scale the design actually has would be
// inventing a design it does not — and because one number for a headline block and a list item is
// precisely what makes a screen read as flat.
//
// A renderer that wants something else should say so where it draws, with a reason. What it must not
// do is write a number again and leave the next reader to work out whether it is a tier or a
// coincidence.
//
// THE RADIUS IS THE BRAND'S AND THE INSET IS OURS, which is not a compromise but the split this build
// already documents: a radius travels with a brand and costs a client release
// (`operator-boundaries`), and an inset is layout. What must move together is the TIER — a headline
// block takes the large radius AND the large inset — and that is what this object is.
//
// THE CANVAS'S OWN TOKEN BLOCK is where the radii come from: `sm 12/8 | md 20/12 | lg 36/22`, brand A
// then brand B, which is exactly `KonektShapeScale`. Several brand-A mockups draw a list card at 22 —
// brand B's large — and the declared token is taken over the drawing: a token block is what a design
// SAYS, and a mockup is a picture of one screen.
internal object CardGeometry {
    enum class Tier(
        val inset: Dp,
    ) {
        // A SCREEN'S HEADLINE BLOCKS: the balance, the allowance, a package's own card. The canvas
        // pairs these with `lg` — the only tier it gives a 36-point radius to.
        CARD(inset = 22.dp),

        // THE ITEMS IN A LIST: a plan, an order, a row that repeats. `md`, and smaller than a
        // headline block on purpose — a list of headline blocks is a screen with no hierarchy.
        ITEM(inset = 18.dp),

        // NOTICES: a banner, a refusal, the degradation block. The canvas draws them at the item's
        // radius and two points tighter, which is the difference between something you read and
        // something you choose from.
        NOTICE(inset = 16.dp),
    }

    // The tier's shape, taken from whichever brand is in force. A composable because the brand is,
    // and because the alternative — passing a scale down through every renderer — is the second
    // argument nobody would keep in step.
    @Composable
    fun shapeOf(tier: Tier): Shape {
        val scale = LocalKonektShapeScale.current
        return when (tier) {
            Tier.CARD -> scale.largeShape
            Tier.ITEM, Tier.NOTICE -> scale.mediumShape
        }
    }
}
