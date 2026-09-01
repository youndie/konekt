package io.konekt.screens

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE FOUR SHAPES WERE COPIED OUT OF THE DESIGN, and a copy is the thing that goes quietly wrong.
//
// `Shell.TabIcons` holds the `d` of each tab's `<svg>` in `docs/design/konekt-esim-app.dc.html`. That
// is a transcription, and a transcription drifts in both directions: somebody edits the constant and
// the design still says otherwise, or the design moves and nothing here notices. Neither shows up on
// screen as an error — it shows up as an icon that is slightly not the one that was drawn, which is
// the kind of wrong nobody reports.
//
// SO THIS READS THE CANVAS. It is in the repository, it is the source these came from, and comparing
// against it costs one file read.
//
// WHAT IT DOES NOT COMPARE is the circles. SVG's `<circle>` is not path data, so two of these icons
// are written here as the arcs that draw them — the one place the copy is a CONVERSION rather than a
// transcription, and one a string comparison cannot check. That gap is named rather than papered
// over: the `<path>` halves of those two icons are still compared, and the arcs are what the
// screenshot goldens are for.
class TabIconsMatchTheCanvasTest {
    private val canvas = Path("../docs/design/konekt-esim-app.dc.html").readText()

    // Each tab's label in the canvas, with the `<svg>` that sits immediately before it.
    private fun canvasPathsFor(label: String): List<String> {
        // THE LAST `<svg>` BEFORE THE LABEL, found by walking backwards rather than by a lazy match
        // forwards. Written the obvious way — `<svg…</svg>\s*<span>label</span>` with a bounded lazy
        // gap — the regex matches from the EARLIEST `<svg` that can still reach the label, which is
        // the previous tab's. It compared Plans against the house and Profile against the document,
        // and both mismatches looked exactly like a design that had moved.
        val at =
            Regex(""">\s*$label\s*<""").find(canvas)?.range?.first
                ?: error("the canvas has no label \"$label\" — the design moved, or the tab was renamed")
        val before = canvas.substring(0, at)
        val opens = before.lastIndexOf("<svg")
        val closes = before.indexOf("</svg>", opens)
        val svg =
            if (opens >= 0 && closes > opens) {
                before.substring(opens, closes)
            } else {
                error("the canvas draws no <svg> before \"$label\"")
            }

        return Regex("""<path[^>]*\bd="([^"]+)"""").findAll(svg).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every path the canvas draws for a tab is one this server sends`() {
        // VACUITY FIRST. This is a regex over an HTML file in another directory: a renamed file, a
        // reformatted export or a changed label gives an empty list, and every comparison below then
        // passes by having nothing to compare.
        assertTrue(canvas.length > 1_000, "the canvas file is empty or missing; this guard is about nothing")

        val missing =
            Shell.Tab.entries.flatMap { tab ->
                val drawn = tab.icon.paths
                val expected = canvasPathsFor(tab.label)
                assertTrue(
                    expected.isNotEmpty(),
                    "the canvas draws no <path> for ${tab.label}, so this tab is compared against nothing",
                )
                expected.filterNot { it in drawn }.map { "${tab.label}: $it" }
            }

        assertEquals(
            emptyList(),
            missing,
            "these are drawn in the canvas and not sent by this server. Either the design moved and " +
                "`Shell.TabIcons` did not follow, or somebody edited the constant",
        )
    }

    // AND THE OTHER DIRECTION, which the test above cannot see: a path invented here that the design
    // has nothing to say about. Only the entries the canvas expresses as `<path>` are checked; the
    // arcs standing in for its two `<circle>`s are allowed, and named, so the exemption cannot quietly
    // grow to cover a shape somebody made up.
    @Test
    fun `nothing is drawn that the canvas does not ask for, beyond the two circles`() {
        val standIns =
            setOf(
                // circle cx=12 cy=12 r=9 — the globe on Plans
                "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
                // circle cx=12 cy=8 r=4 — the head on Profile
                "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
            )

        val invented =
            Shell.Tab.entries.flatMap { tab ->
                val expected = canvasPathsFor(tab.label)
                tab.icon.paths
                    .filterNot { it in expected || it in standIns }
                    .map { "${tab.label}: $it" }
            }

        assertEquals(
            emptyList(),
            invented,
            "these are sent and the canvas does not draw them. A stand-in for one of its <circle> " +
                "elements belongs in `standIns` above, with the circle it replaces written beside it",
        )
    }
}
