package io.konekt.topup

import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.money.MoneyFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// THE FIELD AND THE TEXT BESIDE IT MUST WRITE THE CURRENCY THE SAME WAY.
//
// They did not. `amount_input` has a `currencySuffix` and nothing else — the toolkit can draw the
// symbol after the number and has no way to draw it before (kompot#97) — and the screen filled it
// unconditionally. So a subscriber typing a top-up saw "50 $" six lines above "Between $10 and
// $50,000": one screen, one currency, one response, two conventions (`B-70`).
//
// ASSERTED OVER EVERY CURRENCY IN THE TABLE and not over the one this deployment uses. That is the
// whole point of the item: a hard-coded choice is right for half the table and wrong for the other
// half, and picking the half that happens to be `Currency.DEFAULT` today would leave the test
// agreeing with the bug for every other currency.
class AmountFieldPlacementTest {
    @Test
    fun `the amount field puts the symbol where this currency puts it, or does not put it there at all`() {
        var writtenFirst = 0
        var writtenLast = 0

        Currency.entries.forEach { currency ->
            val field = TopUpScreens.amountField(currency)
            val symbol = MoneyFormat.symbol(currency)
            // How the SAME currency is written when there is an amount to write, which is the thing
            // the field has to agree with — read out of the formatter rather than restated here.
            val formatted = MoneyFormat.format(Money.ofMajor(10, currency))

            if (formatted.startsWith(symbol)) {
                writtenFirst += 1
                // The toolkit cannot draw it in front, so it must not draw it behind instead.
                assertNull(
                    field.currencySuffix,
                    "$currency is written as \"$formatted\" and the field appends the symbol",
                )
                // And it still has to say which currency, somewhere that claims no position.
                assertTrue(
                    symbol in field.label,
                    "$currency's symbol is nowhere on the field: ${field.label}",
                )
            } else {
                writtenLast += 1
                assertEquals(
                    symbol,
                    field.currencySuffix,
                    "$currency is written as \"$formatted\" and the field does not append the symbol",
                )
                // Not twice: the suffix already says it.
                assertTrue(
                    symbol !in field.label,
                    "$currency's symbol is on the field twice: ${field.label} + ${field.currencySuffix}",
                )
            }
        }

        // VACUITY, and it is the assertion that makes the rest mean anything. A table that had drifted
        // to all-prefix or all-suffix would exercise one branch and pass while saying nothing about
        // the other — which is the state this test exists because the product was in.
        assertTrue(writtenFirst > 0, "no currency in the table is written symbol-first; one branch is unchecked")
        assertTrue(writtenLast > 0, "no currency in the table is written symbol-last; one branch is unchecked")
    }

    // The deployment's own currency, stated plainly, because the property above would also be
    // satisfied by a field that said nothing at all about dollars.
    @Test
    fun `the dollar is named on the field and not appended to the number`() {
        val field = TopUpScreens.amountField(Currency.USD)

        assertNull(field.currencySuffix)
        assertEquals("Amount ($)", field.label)
    }
}
