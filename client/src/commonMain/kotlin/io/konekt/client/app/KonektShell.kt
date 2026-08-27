package io.konekt.client.app

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.RowComponent
import io.konekt.components.BottomNavComponent

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
)

fun KompotComponent.withoutShell(): ScreenAndShell {
    val root = this as? ColumnComponent ?: return ScreenAndShell(this, null)
    val nav = root.children.filterIsInstance<BottomNavComponent>()

    return when {
        nav.isEmpty() -> ScreenAndShell(this, null)

        // MORE THAN ONE IS A SERVER MISTAKE AND IS LEFT ALONE. Hoisting the first and dropping the
        // rest would hide it; drawing them where they are makes it visible on the screen it happened
        // on, which is the only place anybody can act on it.
        nav.size > 1 -> ScreenAndShell(this, null)

        else -> ScreenAndShell(root.copy(children = root.children - nav.first()), nav.first())
    }
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
