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

    // THE SYMBOL ALONE, for the one place that needs it without an amount: `amount_input` draws the
    // currency beside a field the subscriber is still typing into, so there is no `Money` to format
    // yet. Reading it out of the same table rather than letting a screen spell "$" keeps one answer
    // to how this deployment writes its currency.
    fun symbol(currency: Currency): String = layouts[currency]?.symbol ?: error("no layout configured for $currency")

    // THE SYMBOL IF THIS CURRENCY WRITES IT AFTER THE AMOUNT, and null if it writes it in front.
    //
    // It exists because `amount_input` has a `currencySuffix` and nothing else: the toolkit can draw
    // the symbol after the number and has no way to draw it before (kompot#97). Two of the five
    // currencies in the table above are written the other way round, so filling that field
    // unconditionally puts the symbol on the wrong side — which is what the top-up screen did, drawing
    // "50 $" six lines above its own "Between $10 and $50,000" (`B-70`).
    //
    // Answering with null rather than with the symbol is the point: it makes "this currency cannot be
    // drawn that way" a case the caller must handle, instead of a placement it can get wrong. The
    // caller's other half — putting the symbol somewhere honest — is a screen decision and lives on
    // the screen.
    fun trailingSymbol(currency: Currency): String? =
        layouts[currency]
            ?.takeUnless { it.symbolFirst }
            ?.symbol

    // A PRICE PER UNIT, and the rounding is the whole of why this is a function rather than a
    // division at a call site.
    //
    // `Money` has no `div` on purpose — dividing money is a rounding decision, and one made
    // implicitly is the one that loses a kopeck per transaction until somebody reconciles a month.
    // That objection does not apply here and the difference is worth stating: **this figure is never
    // charged.** It exists so a subscriber can compare a 5 GB plan against a 20 GB one, it is never
    // summed, never held, never captured, and nobody's balance moves by it. Rounding a comparison
    // aid to the currency's own minor unit costs nothing; refusing to draw one because the division
    // is inexact would hide the comparison the canvas puts it there to make.
    //
    // HALF-UP on the minor unit, spelled with integers rather than with a Double: `1.005` is not
    // representable, and a per-unit price that disagrees with itself between two platforms is worse
    // than one that is a hundredth out.
    fun perUnit(
        total: Money,
        units: Long,
        unitLabel: String,
    ): String? {
        // Zero units is not a price of infinity, it is a plan that includes none of this — and the
        // caller asking about a unit the plan does not carry is the ordinary case rather than a bug.
        if (units <= 0) return null

        // `(2a + b) / 2b`, which is half-up in integers and the first form of this was not: adding
        // one only when there was a remainder rounds 2.5 DOWN, because an exact half has no
        // remainder to notice. The case that caught it is the one a reviewer would skip — five over
        // two — and it is in the tests for that reason.
        //
        // On the MAGNITUDE, with the sign restored: integer division truncates toward zero, so the
        // same expression rounds a negative amount the other way and "half-up" would quietly mean
        // "half-away-from-zero" for half the inputs. Nothing asks this for a negative price today;
        // the day something does, it should not be a surprise.
        val magnitude = if (total.minorUnits < 0) -total.minorUnits else total.minorUnits
        val perUnitMinor = (2 * magnitude + units) / (2 * units)
        val signed = if (total.minorUnits < 0) -perUnitMinor else perUnitMinor
        return "${format(Money(signed, total.currency))} / $unitLabel"
    }

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
