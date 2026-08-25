package io.konekt.money

import io.konekt.domain.Currency
import io.konekt.domain.Money
import kotlin.math.absoluteValue

// The one place money becomes text in this product.
//
// It lives in the server module rather than beside Money in :shared:domain, and that placement is
// the whole mechanism: the client does not depend on this module, so it cannot format an amount even
// by accident. Backend-driven UI makes that costless — the server builds the screen, so the amount
// reaches the client as a string that is already right, and the second copy of the formatter has
// nowhere to appear. The prior art on this stack had exactly two copies, both dividing by a
// hard-coded hundred.
//
// A single locale, the operator's. Grouping by three, a comma before the fraction, the symbol after
// the amount. A second locale would be a real change — placement and separators both move — and it is
// not covered here; it belongs with the operator material of B-30 rather than in a formatter that
// silently guesses.
object MoneyFormat {
    // Non-breaking, where the design's HTML has a plain space. The rendered result is identical until
    // a line break falls between the thousands or before the symbol, and at that point "1" on one
    // line and "190 ₽" on the next is a number nobody can read.
    private const val NBSP = ' '

    // The typographic minus, not the hyphen-minus of a keyboard: it is the width of a digit, so a
    // column of amounts stays aligned.
    private const val MINUS = '−'

    private val symbols =
        mapOf(
            Currency.RUB to "₽",
            Currency.USD to "$",
            Currency.EUR to "€",
            Currency.JPY to "¥",
            // No symbol in common use, so the code stands in. Stated rather than left to a lookup
            // that would return an empty string and produce an amount of nothing.
            Currency.KWD to "KWD",
        )

    fun format(
        money: Money,
        // Whether a positive amount carries an explicit plus. A history row does — the canvas draws
        // "+1 190 ₽" for a top-up — and a balance does not.
        signed: Boolean = false,
    ): String {
        val symbol = symbols[money.currency] ?: error("no symbol configured for ${money.currency}")
        val units = money.currency.minorUnitsPerMajor

        // On the absolute value, so the sign is decided once below rather than surviving a division.
        val absolute = money.minorUnits.absoluteValue
        val major = absolute / units
        val minor = absolute % units

        val sign =
            when {
                money.minorUnits < 0 -> MINUS.toString()
                signed && money.minorUnits > 0 -> "+"
                else -> ""
            }

        // A whole amount drops its zero fraction: the canvas writes "1 190 ₽" and "2 480,50 ₽", and
        // a column of "1 190,00 ₽" is noise in a product where most amounts are whole.
        val fraction = if (minor == 0L) "" else "," + minor.toString().padStart(money.currency.exponent, '0')

        return "$sign${group(major)}$fraction$NBSP$symbol"
    }

    private fun group(major: Long): String =
        major
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(NBSP.toString())
            .reversed()
}
