package io.konekt.components

import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import kotlin.test.Test
import kotlin.test.assertEquals

// A screen, not a component. The registration test proves each type travels alone; this one proves
// they travel nested inside a toolkit container, which is the only way any of them is ever sent.
//
// It is worth its own test because the two failures are different. A missing registration breaks
// both; a `@Polymorphic`-annotation mistake on a child list breaks only this one, and that mistake
// has a history — a plain `call.respond` of a root drops the discriminator while every nested child
// serialises perfectly, so "it works nested" and "it works at the root" are independent facts.
class KonektTreeTest {
    @Test
    fun `a screen of konekt components inside a toolkit column round-trips`() {
        val screen =
            ColumnComponent(
                id = "home",
                children = konektDictionary.map { it.second },
                spacing = 12,
            )

        val decoded = konektTestJson.decodeKompotComponent(konektTestJson.encodeKompotComponent(screen))

        assertEquals(screen, decoded)
    }

    @Test
    fun `an unknown field on a known component is ignored rather than fatal`() {
        // The additive half of the dictionary contract: a newer server may add a field to a component
        // this client already knows, and the client must keep drawing it. Without this the only safe
        // change to any of the nine would be a client release.
        val fromNewerServer =
            """{"type":"step_meter","id":"install-progress","current":3,"total":4,"label":"Install eSIM","estimateText":"about a minute"}"""

        val decoded = konektTestJson.decodeKompotComponent(fromNewerServer)

        assertEquals(
            StepMeterComponent(id = "install-progress", current = 3, total = 4, label = "Install eSIM"),
            decoded,
        )
    }
}
