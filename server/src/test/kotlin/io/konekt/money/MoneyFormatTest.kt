package io.konekt.money

import io.konekt.domain.Currency
import io.konekt.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals

// Asserted against the strings on the design canvas, character for character apart from the spaces,
// which are non-breaking here and plain in the HTML (see MoneyFormat). These are the amounts a
// subscriber actually reads, so the test is the copy review as much as the code review.
class MoneyFormatTest {
    private val nbsp = ' '
    private val minus = '−'

    @Test
    fun `the canvas amounts come out as the canvas draws them`() {
        assertEquals("1${nbsp}190$nbsp₽", MoneyFormat.format(Money(119_000, Currency.RUB)))
        assertEquals("2${nbsp}480,50$nbsp₽", MoneyFormat.format(Money(248_050, Currency.RUB)))
        assertEquals("450$nbsp₽", MoneyFormat.format(Money(45_000, Currency.RUB)))
        assertEquals("149$nbsp₽", MoneyFormat.format(Money(14_900, Currency.RUB)))
    }

    @Test
    fun `a history row carries its sign and a balance does not`() {
        assertEquals("+1${nbsp}190$nbsp₽", MoneyFormat.format(Money(119_000, Currency.RUB), signed = true))
        assertEquals("1${nbsp}190$nbsp₽", MoneyFormat.format(Money(119_000, Currency.RUB), signed = false))
        // The sign of a debit is not optional — it is the difference between money leaving and
        // arriving, and a row that omitted it would read as a top-up.
        assertEquals("${minus}450$nbsp₽", MoneyFormat.format(Money(-45_000, Currency.RUB)))
        assertEquals("${minus}450$nbsp₽", MoneyFormat.format(Money(-45_000, Currency.RUB), signed = true))
    }

    @Test
    fun `an exponent that is not two is formatted by the currency rather than by a hundred`() {
        // The two cases a hard-coded hundred gets wrong, in both directions. A yen has no fraction to
        // print at all; a dinar has three digits of it.
        assertEquals("9${nbsp}999$nbsp¥", MoneyFormat.format(Money(9_999, Currency.JPY)))
        assertEquals("1${nbsp}234,567${nbsp}KWD", MoneyFormat.format(Money(1_234_567, Currency.KWD)))
        assertEquals("1${nbsp}234${nbsp}KWD", MoneyFormat.format(Money(1_234_000, Currency.KWD)))
    }

    @Test
    fun `a leading zero in the fraction survives`() {
        // 5 minor units of a two-exponent currency is five kopecks, not fifty. The padding is what
        // makes that true, and it is the kind of thing that is right until somebody simplifies it.
        assertEquals("0,05$nbsp₽", MoneyFormat.format(Money(5, Currency.RUB)))
        assertEquals("0,005${nbsp}KWD", MoneyFormat.format(Money(5, Currency.KWD)))
        assertEquals("0$nbsp₽", MoneyFormat.format(Money.zero(Currency.RUB)))
    }

    @Test
    fun `grouping starts above a thousand and not below`() {
        assertEquals("999$nbsp₽", MoneyFormat.format(Money(99_900, Currency.RUB)))
        assertEquals("1${nbsp}000$nbsp₽", MoneyFormat.format(Money(100_000, Currency.RUB)))
        assertEquals("1${nbsp}234${nbsp}567$nbsp₽", MoneyFormat.format(Money(123_456_700, Currency.RUB)))
    }
}
