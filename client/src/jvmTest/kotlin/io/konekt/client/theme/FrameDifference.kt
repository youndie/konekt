package io.konekt.client.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.assertEquals

// How two rendered frames are told apart, in the two ways that matter for a brand kit.
//
// ALPHA IS THE GEOMETRY and colour is everything else. A pill becoming a rounded rectangle, a border
// appearing, a control changing size — every one of those changes which pixels are covered.
// Repainting a covered pixel does not. So a comparison that reports `moved == 0` and `repainted > 0`
// is the signature of a kit that changed the colours and nothing else, and one that reports
// `moved > 0` is the signature of a shape scale.
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
            if (a.alpha != b.alpha) moved++
            if (a != b) repainted++
        }
    }

    return FrameDifference(repainted = repainted, moved = moved)
}
