package io.konekt.domain

import kotlinx.serialization.Serializable

// The currencies this product handles, with the one property that makes arithmetic on them possible.
//
// A CLOSED SET, and the reasoning is the opposite of the component dictionary's on purpose. There,
// every enum-shaped field is an open string, because a client meeting an unfamiliar word can draw the
// neutral form and lose nothing but a colour. Here it cannot: an unknown currency has an unknown
// exponent, so a client that accepted it could not format it, could not round it and could not add it
// to anything. Refusing is the only correct behaviour, and an enum is how refusing becomes the
// default rather than something somebody has to remember to write.
//
// The price is real and worth stating: a currency the operator adds is a client release. For a
// single-operator product whose currency set is known at build time that is the right side of the
// trade; for a boxed product sold to several operators it is a row in the table of what is
// configuration and what is a release (B-30).
@Serializable
enum class Currency(
    // How many decimal digits the minor unit has. ISO 4217 calls it the exponent, and it is not two
    // for everybody — which is the whole reason this property exists rather than a constant 100
    // living in a formatter.
    val exponent: Int,
) {
    RUB(2),
    USD(2),
    EUR(2),

    // Zero-exponent: a yen has no subunit at all, so its minor unit IS the yen. Any code that
    // divides by a hundred is wrong here, silently and by a factor of a hundred.
    JPY(0),

    // Three-exponent: 1000 fils to the dinar. The other direction of the same mistake.
    KWD(3),
    ;

    // 1, 100 or 1000 — how many minor units make one major unit. Computed rather than stored so the
    // two can never disagree.
    val minorUnitsPerMajor: Long
        get() {
            var result = 1L
            repeat(exponent) { result *= 10 }
            return result
        }
}
