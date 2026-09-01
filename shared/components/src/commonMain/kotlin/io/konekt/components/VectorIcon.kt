package io.konekt.components

import kotlinx.serialization.Serializable

// AN ICON AS DATA, and that is the whole decision `B-110` had to make.
//
// The bar drew four words because kompot has no icon vocabulary — no wire type, no token — and the
// obvious fix was a NAME plus a table in the client. That is a second dictionary kept in step by
// hand, and its failure is silent: an unknown name draws nothing, and a tab with no icon looks
// exactly like a tab whose icon has not loaded yet.
//
// THE CANVAS SETTLED IT. Its icons are not Material's glyphs — they are stroked line art on a 24-unit
// grid, drawn as SVG paths, and there are 59 of them across the design. A closed enum over
// `Icons.Default` would have drawn DIFFERENT PICTURES than the design asks for, which is not a
// compromise, it is a different product. So either every icon is an asset compiled into the client —
// a client release per picture, for something that carries no behaviour at all — or the shape travels
// on the wire.
//
// It travels. konekt already does this once: `EsimQrComponent` sends a payload and the client draws
// the modules on a `Canvas`. An icon is the same arrangement with a shorter argument, and it means a
// deployment can change its own iconography without anybody shipping a client.
//
// WHAT DOES NOT TRAVEL IS THE COLOUR. The canvas writes `stroke="#5A6663"` on each of these and this
// type deliberately has no such field: a brand kit decides colour, and a server that sent one would
// be the one place a rebrand could not reach. The client strokes the shape in the role the bar asks
// for — selected or not — which is the same reason `plan_card` sends a state and not a colour.
@Serializable
data class VectorIcon(
    // SVG path data, one entry per stroked sub-path. A LIST rather than one string because the
    // canvas draws several of these icons as separate strokes, and joining them into one `d` would
    // close shapes that are meant to stay open.
    val paths: List<String>,
    // The grid the coordinates are on. Sent rather than assumed: an icon authored on a 16-unit grid
    // scaled against a hard-coded 24 is off by half and looks like a rendering bug.
    val viewportSize: Float = 24f,
    // In viewport units, so it scales with the icon rather than staying two pixels at any size.
    val strokeWidth: Float = 2f,
)
