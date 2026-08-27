package io.konekt.client.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.assertEquals

// How two rendered frames are told apart, in the two ways that matter for a brand kit.
//
// COVERAGE IS THE GEOMETRY and colour is everything else. A pill becoming a rounded rectangle, a
// border appearing, a control changing size — every one of those changes WHICH pixels are covered.
// Repainting a covered pixel does not. So a comparison that reports `moved == 0` and `repainted > 0`
// is the signature of a kit that changed the colours and nothing else, and one that reports
// `moved > 0` is the signature of a shape scale.
//
// COVERED, NOT "SAME ALPHA", and the difference was measured rather than reasoned about. This read
// `a.alpha != b.alpha`, which is exact for a filled shape and WRONG for antialiased text on a
// transparent ground: Skia gamma-corrects glyph coverage by the text's luminance, so the same word in
// the same place at the same size produces different edge alphas in two colours. It surfaced the day
// a quiet button stopped being filled — brand A's dark teal and brand B's orange drew "Back"
// identically and reported 171 pixels of movement. Pinning the text colour and leaving everything
// else alone took it to zero, which is what identified it.
//
// A threshold rather than a tolerance: "is this pixel painted at all" is a fact about the drawing,
// while "are these two alphas close enough" is a number that drifts until somebody widens it. The
// guard's own control — two shape scales under one kit must differ — still fires, which is what says
// the sensitivity that matters survived.
//
// The fixtures this reads must therefore paint NO opaque background: with one, every pixel is opaque
// in both frames and `moved` is zero whatever happened. That trap was found by a control test rather
// than by review, and both files that use this carry one.
data class FrameDifference(
    val repainted: Int,
    val moved: Int,
) {
    val identical: Boolean get() = repainted == 0
}

fun compareFrames(
    before: ImageBitmap,
    after: ImageBitmap,
): FrameDifference {
    assertEquals(before.width to before.height, after.width to after.height, "the frame changed size")

    val first = before.toPixelMap()
    val second = after.toPixelMap()
    var repainted = 0
    var moved = 0

    for (y in 0 until before.height) {
        for (x in 0 until before.width) {
            val a = first[x, y]
            val b = second[x, y]
            if ((a.alpha > 0f) != (b.alpha > 0f)) moved++
            if (a != b) repainted++
        }
    }

    return FrameDifference(repainted = repainted, moved = moved)
}
