package io.konekt.client.app

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
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
