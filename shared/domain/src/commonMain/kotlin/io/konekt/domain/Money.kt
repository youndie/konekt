package io.konekt.domain

import kotlinx.serialization.Serializable

// An amount of money: minor units and the currency they are units of, and never one without the
// other.
//
// WHY A TYPE RATHER THAN A HELPER. The nearest prior art on this stack had no money type and a free
// `formatMoney(minorUnits: Long, currency: String)` — written twice, once on a server and once in a
// client view model, each dividing by a hard-coded hundred. A hundred is right for the rouble and
// wrong for the yen and for the dinar, and nothing in a `Long` says which one it is holding. The
// exponent belongs to the currency (see Currency), and a type is what keeps the two together.
//
// WHY THERE IS NO FORMATTER HERE. Formatting lives in the server module, which the client does not
// depend on, so a client cannot format money even by accident. That is backend-driven UI paying for
// itself: the server builds the screen, so the amount reaches the client as text that is already
// right, and the second copy of the formatter has nowhere to appear. The rule is enforced by where
// the code lives rather than by a review.
//
// WHY NOT BigDecimal. It is correct and it serialises badly, compares by scale in a way that
// surprises, and hands the rounding decision to whoever writes the next `divide`. Minor units in a
// Long are exact, order-preserving and the same on every platform this builds for.
@Serializable
data class Money(
    val minorUnits: Long,
    val currency: Currency,
) : Comparable<Money> {
    val isZero: Boolean get() = minorUnits == 0L
    val isNegative: Boolean get() = minorUnits < 0L
    val isPositive: Boolean get() = minorUnits > 0L

    operator fun plus(other: Money): Money = Money(addExact(minorUnits, sameCurrency(other).minorUnits), currency)

    operator fun minus(other: Money): Money = Money(subtractExact(minorUnits, sameCurrency(other).minorUnits), currency)

    // By a whole number only. Multiplying money by a fraction is a rounding decision, and a rounding
    // decision made implicitly is the one that loses a kopeck per transaction until somebody
    // reconciles a month.
    operator fun times(factor: Long): Money = Money(multiplyExact(minorUnits, factor), currency)

    operator fun times(factor: Int): Money = times(factor.toLong())

    operator fun unaryMinus(): Money = Money(negateExact(minorUnits), currency)

    override fun compareTo(other: Money): Int = minorUnits.compareTo(sameCurrency(other).minorUnits)

    private fun sameCurrency(other: Money): Money {
        // Not a warning and not a coercion. Two amounts in different currencies have no sum without a
        // rate, and a rate is a domain operation with a time and a source — not something an operator
        // may invent. See B-31: multi-currency arithmetic is deliberately not covered.
        if (currency != other.currency) {
            throw IllegalArgumentException("cannot combine $currency and ${other.currency} without a conversion rate")
        }
        return other
    }

    companion object {
        fun zero(currency: Currency): Money = Money(0, currency)

        // From whole units, for a literal in a test or a configuration value. `Money.of(1, RUB)` is
        // one rouble, not one kopeck — the exponent does the work, which is the point of having one.
        fun ofMajor(
            majorUnits: Long,
            currency: Currency,
        ): Money = Money(multiplyExact(majorUnits, currency.minorUnitsPerMajor), currency)
    }
}

// Overflow checks, written out because kotlin.Math.addExact is a JVM-only thing and this module also
// compiles for iOS.
//
// A Long of minor units overflows somewhere past ninety quadrillion roubles, so this is not a
// realistic amount — it is cheap insurance against the one failure mode that would otherwise be
// silent. A balance that wraps around is arithmetic that reports a positive number for a negative
// one, and no test written about the business rule would catch it.
private fun addExact(
    a: Long,
    b: Long,
): Long {
    val sum = a + b
    // Overflowed iff the operands share a sign and the result does not.
    if (((a xor sum) and (b xor sum)) < 0) throw ArithmeticException("money addition overflowed: $a + $b")
    return sum
}

private fun subtractExact(
    a: Long,
    b: Long,
): Long {
    val difference = a - b
    if (((a xor b) and (a xor difference)) < 0) throw ArithmeticException("money subtraction overflowed: $a - $b")
    return difference
}

private fun multiplyExact(
    a: Long,
    b: Long,
): Long {
    val product = a * b
    // Division is the check that works without a wider type: if the product is right, dividing it
    // back gives the operand. Long.MIN_VALUE is the case division cannot answer, hence the guard.
    if (a != 0L && (product / a != b || (a == -1L && b == Long.MIN_VALUE))) {
        throw ArithmeticException("money multiplication overflowed: $a * $b")
    }
    return product
}

private fun negateExact(a: Long): Long {
    if (a == Long.MIN_VALUE) throw ArithmeticException("money negation overflowed")
    return -a
}
