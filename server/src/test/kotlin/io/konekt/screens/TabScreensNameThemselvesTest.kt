package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.konektWalk
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.purchase.server.data.HistoryScreen
import io.konekt.feature.purchase.server.domain.HistoryPage
import io.konekt.feature.usage.server.data.StaticUsageAddOns
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.time.KonektClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

// EVERY TAB OPENS BY SAYING WHERE YOU ARE, and one of the four did not.
//
// Home opened with the brand, Plans with "Plans", Profile with "Profile"; Orders opened with its
// filter chips flush against the top edge (`B-72`).
//
// IT IS INVISIBLE TO THE GALLERY BY CONSTRUCTION, which is why a test rather than a frame. Every
// screenshot there is sized to its own content, so a frame of Orders is a perfectly good picture of
// Orders — "this screen starts differently from its three siblings" is a fact about four screens seen
// together and about none of them alone. The same blind spot that hid a bar landing in the middle of
// a window until somebody ran the application.
//
// So the assertion is over the SET. A fifth tab added without a heading fails here, and that is the
// whole point of writing it this way rather than adding one line to the history screen's own test.
class TabScreensNameThemselvesTest {
    private val cards = UsageCounterCards(StaticUsageAddOns(), KonektClock { Instant.fromEpochMilliseconds(0) })

    // The four screens the bottom bar can reach, built with as little as each needs. The HOME entry
    // is given a brand name deliberately: its heading is the operator's display name and is drawn
    // only when the kit carries one — a white-label product that invented a name would print the
    // wrong operator's on the operator's own screen. So the claim about Home is "it names itself when
    // it has a name to give", and a deployment with no display name has no heading there on purpose.
    private fun tabs(): Map<String, KompotComponent> =
        mapOf(
            "Home" to
                HomeScreen.build(
                    msisdn = null,
                    balance = Money.ofMajor(10, Currency.DEFAULT),
                    counters = emptyList(),
                    cards = cards,
                    brandName = "Konekt",
                ),
            "Plans" to PlansScreen.build(plans = emptyList()),
            "Orders" to
                HistoryScreen.build(
                    page = HistoryPage(entries = emptyList(), next = null),
                    titles = { it },
                ),
            "Profile" to
                ProfileScreen.build(
                    ProfileView(msisdn = "+15551234567", esims = EsimHoldings.none, tariffTitle = "Basic"),
                ),
        )

    @Test
    fun `each tab opens with a heading, and it is the first thing on the screen`() {
        val tabs = tabs()
        // Vacuity: a map that lost an entry would pass by having one fewer screen to check.
        assertEquals(4, tabs.size, "a tab was dropped from this list, so it is the one that is unchecked")

        tabs.forEach { (name, screen) ->
            val first =
                (screen as ColumnComponent)
                    .children
                    .firstOrNull()
            assertTrue(
                first is TextComponent && first.style == M3Typography.HeadlineSmall,
                "the $name tab does not open with a heading; it opens with ${first?.let { it::class.simpleName }}",
            )
        }
    }

    // AND THE HEADING SAYS SOMETHING. A blank one would satisfy the shape above and leave the screen
    // looking exactly as it did.
    @Test
    fun `no tab opens with an empty heading`() {
        tabs().forEach { (name, screen) ->
            val heading =
                screen
                    .konektWalk()
                    .filterIsInstance<TextComponent>()
                    .first { it.style == M3Typography.HeadlineSmall }

            assertTrue(heading.text.isNotBlank(), "the $name tab's heading is blank")
        }
    }
}
