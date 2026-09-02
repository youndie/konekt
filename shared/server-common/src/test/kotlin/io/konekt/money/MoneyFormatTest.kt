package io.konekt.money

import io.konekt.domain.Currency
import io.konekt.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals

// The strings a subscriber actually reads, so this test is the copy review as much as the code
// review.
class MoneyFormatTest {
    // SPELLED AS AN ESCAPE, not typed: a no-break space in a source file is indistinguishable from a
    // space to every reader, and for a while both this constant and the formatter's were plain spaces
    // agreeing with each other — a test that had never checked the one thing its name promised.
    private val nbsp = '\u00A0'
    private val minus = '−'

    @Test
    fun `dollars are written the way dollars are written`() {
        // Symbol in front, groups by comma, fraction by point, and no space between the two — which
        // is four decisions, none of which the amount itself carries.
        assertEquals("$1,190.50", MoneyFormat.format(Money(119_050, Currency.USD)))
        assertEquals("$1,190", MoneyFormat.format(Money(119_000, Currency.USD)))
        assertEquals("$450", MoneyFormat.format(Money(45_000, Currency.USD)))
        assertEquals("$1,234,567.89", MoneyFormat.format(Money(123_456_789, Currency.USD)))
    }

    @Test
    fun `the default currency is the one the product runs in`() {
        // Named once, so a change of currency is one edit rather than a search. The assertion is
        // here rather than in the domain because this is the place the choice becomes visible.
        assertEquals(Currency.USD, Currency.DEFAULT)
        assertEquals("$1,190", MoneyFormat.format(Money.ofMajor(1_190, Currency.DEFAULT)))
    }

    @Test
    fun `a history row carries its sign and a balance does not`() {
        assertEquals("+$1,190", MoneyFormat.format(Money(119_000, Currency.USD), signed = true))
        assertEquals("$1,190", MoneyFormat.format(Money(119_000, Currency.USD), signed = false))
        // The sign leads, ahead of the symbol. "$−450" is what a naive concatenation produces and it
        // reads as a currency nobody has.
        assertEquals("$minus$450", MoneyFormat.format(Money(-45_000, Currency.USD)))
        // The sign of a debit is not optional — it is the difference between money leaving and
        // arriving, and a row that omitted it would read as a top-up.
        assertEquals("$minus$450", MoneyFormat.format(Money(-45_000, Currency.USD), signed = true))
    }

    @Test
    fun `a currency written the other way round still comes out right`() {
        // The rouble is here because the design canvas is drawn in it, and because a table with one
        // row in it proves nothing about being a table. Symbol after, non-breaking space, comma
        // before the fraction.
        assertEquals("1${nbsp}190,50$nbsp₽", MoneyFormat.format(Money(119_050, Currency.RUB)))
        assertEquals("1${nbsp}190$nbsp₽", MoneyFormat.format(Money(119_000, Currency.RUB)))
        assertEquals("${minus}450$nbsp₽", MoneyFormat.format(Money(-45_000, Currency.RUB)))
    }

    @Test
    fun `an exponent that is not two is formatted by the currency rather than by a hundred`() {
        // The two cases a hard-coded hundred gets wrong, in both directions. A yen has no fraction to
        // print at all; a dinar has three digits of it.
        assertEquals("¥9,999", MoneyFormat.format(Money(9_999, Currency.JPY)))
        assertEquals("1,234.567${nbsp}KWD", MoneyFormat.format(Money(1_234_567, Currency.KWD)))
        assertEquals("1,234${nbsp}KWD", MoneyFormat.format(Money(1_234_000, Currency.KWD)))
    }

    @Test
    fun `a leading zero in the fraction survives`() {
        // 5 minor units of a two-exponent currency is five cents, not fifty. The padding is what
        // makes that true, and it is the kind of thing that is right until somebody simplifies it.
        assertEquals("$0.05", MoneyFormat.format(Money(5, Currency.USD)))
        assertEquals("0.005${nbsp}KWD", MoneyFormat.format(Money(5, Currency.KWD)))
        assertEquals("$0", MoneyFormat.format(Money.zero(Currency.USD)))
    }

    @Test
    fun `grouping starts above a thousand and not below`() {
        assertEquals("$999", MoneyFormat.format(Money(99_900, Currency.USD)))
        assertEquals("$1,000", MoneyFormat.format(Money(100_000, Currency.USD)))
    }

    @Test
    fun `every currency has a layout`() {
        // The guard the map needs: a currency added to the enum without a row here fails at runtime
        // with "no layout configured", which is a screen that cannot be built rather than an amount
        // that looks odd. Better to find it now, per currency rather than by count.
        Currency.entries.forEach { currency ->
            MoneyFormat.format(Money(1_234_567, currency))
        }
    }
}
