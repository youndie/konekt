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
// WHY A TABLE RATHER THAN java.text.NumberFormat. This module is JVM-only, so the platform formatter
// is available, and it is the wrong tool here for one reason: its output is CLDR data, which moves
// between JDK releases. The space in a currency format has changed character more than once, and a
// test asserting the exact string a subscriber reads would then break on a toolchain upgrade with no
// change to this repository. Fifteen lines of table are stable, diffable and reviewable.
object MoneyFormat {
    // Non-breaking, where the design's HTML has a plain space. The rendered result is identical until
    // a line break falls between the thousands or before the symbol, and at that point "1" on one
    // line and "190 ₽" on the next is a number nobody can read.
    private const val NBSP = ' '

    // The typographic minus, not the hyphen-minus of a keyboard: it is the width of a digit, so a
    // column of amounts stays aligned.
    private const val MINUS = '−'

    // How each currency is written. Not a locale in the java.util sense — a currency is written the
    // same way wherever this product shows it, because the product has one audience per deployment.
    private data class Layout(
        val symbol: String,
        // A dollar sits in front of its amount and a rouble after it. Getting this from a table
        // rather than from a flag on the amount is what keeps a screen from having to know.
        val symbolFirst: Boolean,
        val groupSeparator: Char,
        val decimalSeparator: Char,
        // "$1,190" has none; "1 190 ₽" has one. It is part of how the currency is written rather
        // than a style choice.
        val spaceBeforeSymbol: Boolean,
    )

    private val layouts =
        mapOf(
            Currency.USD to
                Layout(
                    "$",
                    symbolFirst = true,
                    groupSeparator = ',',
                    decimalSeparator = '.',
                    spaceBeforeSymbol = false,
                ),
            Currency.EUR to
                Layout(
                    "€",
                    symbolFirst = false,
                    groupSeparator = NBSP,
                    decimalSeparator = ',',
                    spaceBeforeSymbol = true,
                ),
            Currency.RUB to
                Layout(
                    "₽",
                    symbolFirst = false,
                    groupSeparator = NBSP,
                    decimalSeparator = ',',
                    spaceBeforeSymbol = true,
                ),
            Currency.JPY to
                Layout(
                    "¥",
                    symbolFirst = true,
                    groupSeparator = ',',
                    decimalSeparator = '.',
                    spaceBeforeSymbol = false,
                ),
            // No symbol in common use, so the code stands in. Stated rather than left to a lookup
            // that would return an empty string and produce an amount of nothing.
            Currency.KWD to
                Layout(
                    "KWD",
                    symbolFirst = false,
                    groupSeparator = ',',
                    decimalSeparator = '.',
                    spaceBeforeSymbol = true,
                ),
        )

    fun format(
        money: Money,
        // Whether a positive amount carries an explicit plus. A history row does — the canvas draws a
        // top-up with one — and a balance does not.
        signed: Boolean = false,
    ): String {
        val layout = layouts[money.currency] ?: error("no layout configured for ${money.currency}")
        val units = money.currency.minorUnitsPerMajor

        // On the absolute value, so the sign is decided once below rather than surviving a division.
        val absolute = money.minorUnits.absoluteValue
        val major = absolute / units
        val minor = absolute % units

        // A whole amount drops its zero fraction: "$1,190" and "$1,190.50", because a column of
        // "$1,190.00" is noise in a product where most amounts are whole.
        val fraction =
            if (minor == 0L) {
                ""
            } else {
                layout.decimalSeparator + minor.toString().padStart(money.currency.exponent, '0')
            }

        val amount = group(major, layout.groupSeparator) + fraction
        val gap = if (layout.spaceBeforeSymbol) NBSP.toString() else ""

        // The sign leads, ahead of a prefixed symbol: "−$450", not "$−450".
        val sign =
            when {
                money.minorUnits < 0 -> MINUS.toString()
                signed && money.minorUnits > 0 -> "+"
                else -> ""
            }

        return if (layout.symbolFirst) {
            "$sign${layout.symbol}$gap$amount"
        } else {
            "$sign$amount$gap${layout.symbol}"
        }
    }

    private fun group(
        major: Long,
        separator: Char,
    ): String =
        major
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(separator.toString())
            .reversed()
}
