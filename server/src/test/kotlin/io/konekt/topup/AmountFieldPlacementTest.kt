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
// They did not. `amount_input` had a `currencySuffix` and nothing else — the toolkit could draw the
// symbol after the number and had no way to draw it before (kompot#97, closed in `0.33.1.93`) — and
// the screen filled it unconditionally. So a subscriber typing a top-up saw "50 $" six lines above
// "Between $10 and $50,000": one screen, one currency, one response, two conventions (`B-70`).
//
// The field takes both sides now and the workaround that put the symbol in the label is gone, so what
// this asserts has changed while the property has not: the symbol goes where this currency puts it,
// and on one side only.
//
// ASSERTED OVER EVERY CURRENCY IN THE TABLE and not over the one this deployment uses. A hard-coded
// choice is right for half the table and wrong for the other half, and a test written about
// `Currency.DEFAULT` would have agreed with the bug for every other currency.
class AmountFieldPlacementTest {
    @Test
    fun `the amount field puts the symbol where this currency puts it, and on one side only`() {
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
                assertEquals(
                    symbol,
                    field.currencyPrefix,
                    "$currency is written as \"$formatted\" and the field does not lead with the symbol",
                )
                assertNull(
                    field.currencySuffix,
                    "$currency is written as \"$formatted\" and the field also appends the symbol",
                )
            } else {
                writtenLast += 1
                assertEquals(
                    symbol,
                    field.currencySuffix,
                    "$currency is written as \"$formatted\" and the field does not append the symbol",
                )
                assertNull(
                    field.currencyPrefix,
                    "$currency is written as \"$formatted\" and the field also leads with the symbol",
                )
            }

            // NEVER IN THE LABEL, on either branch. That was the workaround while the toolkit had one
            // side, and a label that kept saying "($)" beside a field that now draws it would be the
            // same defect wearing the other hat.
            assertTrue(symbol !in field.label, "$currency's symbol is in the label as well: ${field.label}")
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
    fun `the dollar leads the number, as it does everywhere else in this product`() {
        val field = TopUpScreens.amountField(Currency.USD)

        assertEquals("$", field.currencyPrefix)
        assertNull(field.currencySuffix)
        assertEquals("Amount", field.label)
    }
}
