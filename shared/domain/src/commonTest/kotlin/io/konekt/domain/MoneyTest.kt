package io.konekt.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {
    private val json = Json

    @Test
    fun `two amounts in different currencies cannot be combined`() {
        val roubles = Money(1_000, Currency.RUB)
        val dollars = Money(1_000, Currency.USD)

        // Throwing, not coercing and not warning: a sum of two currencies needs a rate, and a rate
        // has a time and a source. An operator that invented one would be inventing money.
        assertFailsWith<IllegalArgumentException> { roubles + dollars }
        assertFailsWith<IllegalArgumentException> { roubles - dollars }
        assertFailsWith<IllegalArgumentException> { roubles < dollars }
    }

    @Test
    fun `the exponent belongs to the currency rather than to a constant hundred`() {
        // The three shapes the world actually has, and the reason a free formatMoney(Long, String)
        // dividing by 100 is wrong twice over.
        assertEquals(100L, Currency.RUB.minorUnitsPerMajor)
        assertEquals(1L, Currency.JPY.minorUnitsPerMajor)
        assertEquals(1_000L, Currency.KWD.minorUnitsPerMajor)

        assertEquals(Money(500, Currency.RUB), Money.ofMajor(5, Currency.RUB))
        assertEquals(Money(5, Currency.JPY), Money.ofMajor(5, Currency.JPY))
        assertEquals(Money(5_000, Currency.KWD), Money.ofMajor(5, Currency.KWD))
    }

    @Test
    fun `the wire form preserves exact minor units at every exponent`() {
        // Per currency rather than once: a round trip that only ever sees two-decimal money proves
        // nothing about the two cases where the exponent is not two, and those are the ones a
        // hard-coded hundred gets wrong.
        listOf(
            Money(1_190_00, Currency.RUB),
            Money(9_999, Currency.JPY),
            Money(1_234_567, Currency.KWD),
        ).forEach { original ->
            val decoded = json.decodeFromString(Money.serializer(), json.encodeToString(Money.serializer(), original))

            assertEquals(original, decoded, "${original.currency} did not survive the wire")
            assertEquals(original.minorUnits, decoded.minorUnits)
        }
    }

    @Test
    fun `the wire form is minor units and a code and never a formatted string`() {
        val encoded = json.encodeToString(Money.serializer(), Money(2_480_50, Currency.RUB))

        // Asserted on the text because this IS the contract. A formatted string on the wire is
        // unusable for arithmetic on the other side and invites a client to re-parse its own display.
        assertEquals("""{"minorUnits":248050,"currency":"RUB"}""", encoded)
    }

    @Test
    fun `arithmetic keeps the currency and the sign`() {
        val balance = Money.ofMajor(1_000, Currency.RUB)

        assertEquals(Money.ofMajor(1_450, Currency.RUB), balance + Money.ofMajor(450, Currency.RUB))
        assertEquals(Money.ofMajor(550, Currency.RUB), balance - Money.ofMajor(450, Currency.RUB))
        assertEquals(Money.ofMajor(3_000, Currency.RUB), balance * 3)
        assertEquals(Money(-100_000, Currency.RUB), -balance)
        assertTrue(Money.zero(Currency.RUB).isZero)
        assertTrue((-balance).isNegative)
    }

    @Test
    fun `overflow throws rather than wrapping`() {
        // The only failure mode here that would be silent. A wrapped balance reports a positive
        // number for a negative one, and no test about a business rule would notice.
        val huge = Money(Long.MAX_VALUE, Currency.RUB)

        assertFailsWith<ArithmeticException> { huge + Money(1, Currency.RUB) }
        assertFailsWith<ArithmeticException> { huge * 2 }
        assertFailsWith<ArithmeticException> { Money(Long.MIN_VALUE, Currency.RUB) - Money(1, Currency.RUB) }
        assertFailsWith<ArithmeticException> { -Money(Long.MIN_VALUE, Currency.RUB) }
        assertFailsWith<ArithmeticException> { Money.ofMajor(Long.MAX_VALUE, Currency.RUB) }
    }

    @Test
    fun `amounts order within one currency`() {
        val amounts = listOf(Money(300, Currency.RUB), Money(-50, Currency.RUB), Money(0, Currency.RUB))

        assertEquals(
            listOf(Money(-50, Currency.RUB), Money(0, Currency.RUB), Money(300, Currency.RUB)),
            amounts.sorted(),
        )
    }
}
