package io.konekt.client.app

import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BottomNavComponent
import io.konekt.components.BottomNavItem
import io.konekt.components.SurfaceComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// A `surface` the server marks `pinned` is the screen's footer (`B-114`, block 2): the buy button
// with `Charged once` over it stays above the bar while the rest scrolls. This is the seam that
// decides it — and it was written after a frame showed the footer drawn in place, because the shell
// pulled the bar out and nothing pulled the footer.
class PinnedFooterLeavesTheScrollTest {
    private val body = TextComponent(id = "body", text = "the plan")
    private val footer =
        SurfaceComponent(id = "footer", pinned = true, children = listOf(TextComponent(id = "buy", text = "Buy")))
    private val bar =
        BottomNavComponent(
            id = "shell-nav",
            items = listOf(BottomNavItem(label = "Home", action = NavigateAction("app://home"))),
        )

    @Test
    fun `the pinned surface comes out with the bar, and the content keeps the rest`() {
        val shell = ColumnComponent(id = "root", children = listOf(body, footer, bar)).withoutShell()

        assertEquals(footer, shell.footer)
        assertEquals(bar, shell.nav)
        assertEquals(listOf(body), (shell.content as ColumnComponent).children)
    }

    // A screen without a bar — the purchase result — still gets its footer pulled: the two are
    // independent, and the branch that only looked for the bar left this one in the scroll.
    @Test
    fun `a footer without a bar is still a footer`() {
        val shell = ColumnComponent(id = "root", children = listOf(body, footer)).withoutShell()

        assertEquals(footer, shell.footer)
        assertNull(shell.nav)
        assertEquals(listOf(body), (shell.content as ColumnComponent).children)
    }

    // Two pinned surfaces is a server mistake, and the honest drawing is the one that shows it.
    @Test
    fun `two pinned surfaces are drawn where they were sent`() {
        val second = footer.copy(id = "footer-2")
        val tree = ColumnComponent(id = "root", children = listOf(body, footer, second))

        val shell = tree.withoutShell()

        assertNull(shell.footer)
        assertEquals(tree, shell.content)
    }

    // And a surface that is not pinned is content, whatever else it carries.
    @Test
    fun `an unpinned surface stays in the scroll`() {
        val card = footer.copy(pinned = false)
        val shell = ColumnComponent(id = "root", children = listOf(card, bar)).withoutShell()

        assertNull(shell.footer)
        assertEquals(listOf(card), (shell.content as ColumnComponent).children)
    }
}
