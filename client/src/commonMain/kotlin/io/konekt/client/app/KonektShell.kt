package io.konekt.client.app

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.RowComponent
import io.konekt.components.BottomNavComponent
import io.konekt.components.ScreenHeaderComponent
import io.konekt.components.SurfaceComponent

// TAKING THE BAR OUT OF THE TREE IT ARRIVED IN.
//
// `BottomNavComponent` says it in its own comment: the bar is not part of the screen, it is the
// shell around every screen. The server sends it INSIDE the tree because the server is the only side
// that knows which tab it just built — and drawn where it arrives, it lands wherever the content
// happens to end. On the home screen that was a third of the way down.
//
// So the client lifts it out and draws it at the bottom of the window. That is the half of `B-49`
// that shipped without being finished, and the photograph in `B-51` is what made it obvious.
//
// ONLY THE ROOT'S OWN CHILDREN ARE SEARCHED, and not the whole tree. A bar nested three levels down
// is not a shell — it is a screen that drew a bar in the middle of itself, and hoisting that would
// be the client quietly rearranging a layout the server meant. One level is exactly the contract:
// the server appends it last to the root.
data class ScreenAndShell(
    val content: KompotComponent,
    val nav: BottomNavComponent?,
    // A `surface` the server marked `pinned` (`B-114`): kept at the bottom above the bar, outside
    // the scroll, the way the canvas pins a plan's buy button. One at most — a second stays in
    // the content and is drawn in place, which is how a frame with two footers gets noticed.
    val footer: SurfaceComponent? = null,
    // A `screen_header` the server sent first (`B-115`): the screen's own back control and title,
    // drawn by the shell in the chevron's place. One at most, for the reason the footer gives.
    val header: ScreenHeaderComponent? = null,
)

fun KompotComponent.withoutShell(): ScreenAndShell {
    val root = this as? ColumnComponent ?: return ScreenAndShell(this, null)
    val nav = root.children.filterIsInstance<BottomNavComponent>()
    val pinned = root.children.filterIsInstance<SurfaceComponent>().filter { it.pinned }
    val headers = root.children.filterIsInstance<ScreenHeaderComponent>()

    // ONE OF EACH OR NONE: two bars is a tree this build does not understand, and the honest answer
    // is to draw it as sent. The same for two pinned surfaces — a frame with two footers is a
    // server mistake that should be visible, not one this code quietly picks a winner from.
    val bar = nav.singleOrNull()
    val footer = pinned.singleOrNull()
    val header = headers.singleOrNull()
    if (bar == null && footer == null && header == null) return ScreenAndShell(this, null)

    return ScreenAndShell(
        content = root.copy(children = root.children - listOfNotNull(bar, footer, header).toSet()),
        nav = bar,
        footer = footer,
        header = header,
    )
}

// WHETHER THE FRAME MAY SCROLL THE SCREEN FOR IT.
//
// Nothing in this application scrolled. `KompotScreen` does not, `ColumnRenderer` does not, and a
// screen taller than the window was simply cut off — which nobody had noticed while the tallest
// screen was a balance and one counter. The frame is the one place that can add it, because it is
// the one place that owns the window.
//
// IT CANNOT ALWAYS. A `paginated_list` is a lazy column, and a lazy column inside a vertical scroll
// is measured with an infinite maximum height — Compose throws rather than draws. So a tree that
// carries one scrolls ITSELF and the frame keeps out of the way; everything else gets the scroll it
// never had.
//
// The whole tree is searched rather than the root's children, and here that is the right depth: a
// lazy list nested anywhere in the tree breaks a scroll wrapped around all of it.
fun KompotComponent.carriesItsOwnScroll(): Boolean =
    when (this) {
        is PaginatedListComponent -> true
        is ColumnComponent -> children.any { it.carriesItsOwnScroll() }
        is RowComponent -> children.any { it.carriesItsOwnScroll() }
        else -> false
    }
