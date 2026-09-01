package io.konekt.client.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.konekt.components.VectorIcon

// DRAWING A SHAPE THE SERVER SENT, which is `B-110`'s whole answer and is not a new idea here:
// `EsimQrRenderer` already takes a payload off the wire and draws it on a `Canvas`. This takes SVG
// path data and does the same, so a deployment can change its own iconography without a client
// release for a picture that carries no behaviour.
//
// `PathParser` IS COMPOSE'S OWN, not a parser written here. That matters more than it looks: SVG path
// grammar has arcs, implicit repeats and relative commands, and a hand-rolled reader would be a place
// for bugs that draw *something* — which is the worst failure available, because a wrong picture and
// a right one are both pictures.
//
// UNREADABLE PATH DATA DRAWS NOTHING, and that IS the silent failure the name-plus-table approach was
// rejected for — so it is not left to chance: `VectorIconsAreDrawableTest` parses every icon this build
// sends and refuses one that yields an empty path.
@Composable
internal fun VectorIconGlyph(
    icon: VectorIcon,
    color: Color,
    size: Dp = 20.dp,
) {
    // Parsed once per icon rather than on every recomposition. The bar redraws on every screen and
    // on every press; re-reading four path strings each time is work nobody asked for.
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }

    Canvas(modifier = Modifier.size(size)) {
        // The icon is authored on its own grid and drawn at whatever size the bar wants, so the whole
        // thing is scaled rather than each coordinate. `viewportSize` is on the wire for exactly this
        // — an icon drawn on a 16-unit grid against a hard-coded 24 is off by half, which reads as a
        // rendering bug rather than as the mismatch it is.
        val factor = this.size.minDimension / icon.viewportSize
        val inset = (this.size.maxDimension - this.size.minDimension) / 2

        translate(
            left =
                if (this.size.width >
                    this.size.height
                ) {
                    inset
                } else {
                    0f
                },
            top = if (this.size.height > this.size.width) inset else 0f,
        ) {
            scale(scale = factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                paths.forEach { path ->
                    drawPath(
                        path = path,
                        color = color,
                        // STROKED AND NOT FILLED, because the canvas's icons are outlines: filling
                        // `M4 5h16v14H4z` gives a solid rectangle where the design has a document.
                        //
                        // Round caps and joins are the canvas's own (`stroke-linecap:round`), and the
                        // width is in viewport units so it scales with the shape instead of staying
                        // two pixels at any size.
                        style =
                            Stroke(
                                width = icon.strokeWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                    )
                }
            }
        }
    }
}
