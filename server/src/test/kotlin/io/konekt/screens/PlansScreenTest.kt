package io.konekt.screens

import io.konekt.components.PlanStates
import io.konekt.feature.purchase.server.data.StaticPlanCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// THE CARD SPEAKS ONLY WHEN IT HAS SOMETHING TO SAY (`B-114`, block 4). `On sale` under every card
// read as a warning about the ordinary case; the canvas tags the sold-out card and leaves the rest
// alone, and puts a `Choose` pill on the cards that can be chosen — which the sold-out one cannot.
class PlansScreenTest {
    private val plans = runBlocking { StaticPlanCatalog().all() }

    @Test
    fun `a plan on sale carries the pill and no tag`() {
        val onSale = plans.filter { it.onSale }
        assertEquals(3, onSale.size, "the catalogue changed shape and this test reads a different one")

        onSale.map(PlansScreen::card).forEach { card ->
            assertEquals(PlanStates.AVAILABLE, card.state)
            assertEquals("Choose", card.actionText, "${card.id} cannot be chosen from the list")
            assertNotNull(card.action, "${card.id} has a pill and nothing for it to press")
            assertNull(card.badgeText, "${card.id} says something about being ordinary: ${card.badgeText}")
        }
    }

    @Test
    fun `the sold-out plan is tagged and cannot be chosen`() {
        val soldOut = plans.single { !it.onSale }
        val card = PlansScreen.card(soldOut)

        assertEquals(PlanStates.SOLD_OUT, card.state)
        assertEquals("Sold out", card.badgeText)
        assertNull(card.actionText, "a plan that cannot be bought offers to be chosen")
    }
}
