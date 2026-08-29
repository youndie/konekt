package io.konekt.packages

import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.data.StaticPlanCatalog
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.roaming.server.domain.Zones
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// THE ID IS UNTRUSTED INPUT, and that is the whole subject of this file.
//
// A custom package has no row: the id carries the three quantities, so anything that can reach
// `POST /api/v1/purchases` can name one. If the catalogue parsed three numbers and priced them, a
// caller could order 9999 GB at whatever `priceOf` returns for it — the purchase saga would take the
// plan, hold the money and grant the entitlement, and every interceptor would be behaving correctly
// over a package nobody was ever offered.
class CustomPackagePlansTest {
    private val plans = CustomPackagePlans(StaticPlanCatalog())

    @Test
    fun `a built package resolves to a plan priced by the tariff`() =
        runTest {
            val plan = plans.find(CustomPackagePlans.idOf(dataGb = 10, minutes = 300, messages = 50))

            assertNotNull(plan, "an id the builder composes does not resolve")
            // 10 × 150 + 300 × 2 + 50 × 1 = 2150 minor units. Spelled as the arithmetic rather than as
            // a number, so a tariff change moves this line for a reason a reader can check.
            assertEquals(Money(10 * 150 + 300 * 2 + 50 * 1, Currency.DEFAULT), plan.price)
            assertEquals(10 * 1_024L, plan.dataMb, "the allowance is not the data that was chosen")
            assertEquals(300, plan.minutes)
            assertEquals(50, plan.messages)
            // A HOME package. Roaming ones lie dormant until arrival, which is a different product and
            // one the catalogue lists.
            assertEquals(Zones.HOME, plan.zone)
            assertTrue(plan.onSale)
        }

    // THE REFUSAL THAT MATTERS. Every one of these is an id a caller can type, and each resolves to
    // nothing — which the purchase use case turns into a 404 rather than into an order.
    @Test
    fun `an id nobody was offered resolves to nothing`() =
        runTest {
            listOf(
                "custom-9999-0-0" to "a data quantity outside the steps",
                "custom-0-7-0" to "a minute quantity outside the steps",
                "custom-0-0-3" to "a message quantity outside the steps",
                "custom-10-300" to "two quantities instead of three",
                "custom-10-300-50-1" to "four quantities instead of three",
                "custom-ten-300-50" to "a quantity that is not a number",
                "custom--10-300-50" to "a negative quantity, which no step is",
            ).forEach { (id, why) ->
                assertNull(plans.find(id), "$why was accepted: $id")
            }
        }

    // VACUITY, in the direction that matters: a parser that answered `null` to everything would pass
    // the test above and break the product. The listed catalogue still resolves, and a custom id still
    // does — asserted here so the refusals above are refusals rather than a broken parser.
    @Test
    fun `the listed catalogue still answers, and custom packages are not in it`() =
        runTest {
            assertNotNull(plans.find("home-20gb-30d"), "the listed catalogue no longer resolves")
            assertNotNull(plans.find(CustomPackagePlans.idOf(1, 0, 0)), "a built package no longer resolves")

            val listed = plans.all()
            assertEquals(StaticPlanCatalog.DEFAULT.map(Plan::id), listed.map(Plan::id))
            assertTrue(
                listed.none { it.id.startsWith(CustomPackagePlans.PREFIX) },
                "a custom package is in the catalogue screen, where there are as many as there are combinations",
            )
        }

    // THE FORM OPENS ON A PACKAGE OF NOTHING — three zeros, priced at nothing — so the empty package
    // has to stay resolvable and must not be orderable. Two different rules, and putting the second
    // one in the parser would have broken the first.
    @Test
    fun `a package of nothing resolves and cannot be ordered`() =
        runTest {
            assertNotNull(plans.find(CustomPackagePlans.idOf(0, 0, 0)), "the state the form opens on does not resolve")

            val refused =
                runCatching {
                    CustomPackagePlans.requireSomethingChosen(CustomPackageQuantities(0, 0, 0))
                }.exceptionOrNull()
            assertNotNull(refused, "an empty package was accepted as an order")

            // And something chosen is not refused, in each of the three directions — otherwise the
            // assertion above is satisfied by a rule that refuses everything.
            listOf(
                CustomPackageQuantities(1, 0, 0),
                CustomPackageQuantities(0, 100, 0),
                CustomPackageQuantities(0, 0, 50),
            ).forEach { CustomPackagePlans.requireSomethingChosen(it) }
        }

    // The title is what the order history calls it three months later, where there is no card under
    // the row to carry the rest. Composed on the server like every other string.
    @Test
    fun `the title names what was chosen and leaves out what was not`() =
        runTest {
            assertEquals(
                "Your package · 10 GB · 300 min · 50 SMS",
                plans.find(CustomPackagePlans.idOf(10, 300, 50))?.title,
            )
            assertEquals("Your package · 5 GB", plans.find(CustomPackagePlans.idOf(5, 0, 0))?.title)
            assertEquals("Your package · 200 SMS", plans.find(CustomPackagePlans.idOf(0, 0, 200))?.title)
        }
}
