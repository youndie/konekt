package io.konekt.money

import io.konekt.domain.Currency
import io.konekt.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// A COMPARISON FIGURE, and the tests are about the two ways it goes wrong: the rounding, and the
// unit it is quoted in.
//
// The second is not hypothetical. The first version of the plan card divided by MEGABYTES and wrote
// "GB" under the answer — off by 1024 in the direction that makes every plan look free, and nothing
// about the number on screen said so.
class MoneyPerUnitTest {
    private fun usd(minor: Long) = Money(minor, Currency.DEFAULT)

    @Test
    fun `an exact division reads as the amount it is`() {
        // $12 over 10 units.
        assertEquals("$1.20 / GB", MoneyFormat.perUnit(usd(1_200), 10, "GB"))
        assertEquals("$0.75 / GB", MoneyFormat.perUnit(usd(1_500), 20, "GB"))
    }

    @Test
    fun `an inexact division rounds half up on the currency's own minor unit`() {
        // $10 over 3 is 3.333…, and $10 over 6 is 1.666… — one rounds down, one rounds up, and both
        // have to be spelled with integers: 1.005 is not representable as a Double, and a per-unit
        // price that disagrees with itself between two platforms is worse than one a hundredth out.
        assertEquals("$3.33 / GB", MoneyFormat.perUnit(usd(1_000), 3, "GB"))
        assertEquals("$1.67 / GB", MoneyFormat.perUnit(usd(1_000), 6, "GB"))
    }

    // THE CASE THE FIRST FORMULA GOT WRONG, and the one a reviewer skips. `(a/b + 1 if remainder)/2`
    // rounds an exact half DOWN — because an exact half leaves no remainder to notice — so five over
    // two answered $0.02 where half-up is $0.03.
    @Test
    fun `exactly a half goes up rather than to even`() {
        // 5 over 2 is 2.5 minor units. Half-up is the rule a person expects from a price, and
        // banker's rounding here would answer "$0.02" for one plan and "$0.02" for its neighbour in a
        // way nobody can predict from the label.
        assertEquals("$0.03 / GB", MoneyFormat.perUnit(usd(5), 2, "GB"))
    }

    // NOTHING TO DIVIDE BY IS NOT A PRICE OF INFINITY. A plan that includes no data has no price per
    // gigabyte, and a card drawing "$0.00 / GB" would be answering a question nobody asked.
    @Test
    fun `a plan with none of the unit gets no figure`() {
        assertNull(MoneyFormat.perUnit(usd(1_200), 0, "GB"))
        assertNull(MoneyFormat.perUnit(usd(1_200), -1, "GB"))
    }

    // THE UNIT IS THE CALLER'S TO SCALE, and this is the case that names why. A plan holds megabytes;
    // asking for a price per gigabyte means scaling the PRICE by 1024, not dividing by a rounded
    // quota — 20 GB is 20480 MB, and `$15 × 1024 ÷ 20480` is exactly $0.75 with no rounding at all.
    @Test
    fun `a price per gigabyte from a quota held in megabytes`() {
        val dataMb = 20L * 1_024
        assertEquals("$0.75 / GB", MoneyFormat.perUnit(usd(1_500) * 1_024L, dataMb, "GB"))
    }
}
