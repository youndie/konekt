package io.konekt.feature.auth.server.domain

import io.konekt.domain.KonektException

// A subscriber's number in one canonical form: digits only, no plus, no spaces, no punctuation.
//
// The type exists so that the normalisation happens once. A number stored two ways is a subscriber
// who can sign in twice and own two balances, and the two spellings are not exotic — "+1 (555)
// 010-9999" and "15550109999" are the same person typing on two days.
@JvmInline
value class Msisdn private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        // Long enough to be a number, short enough to be one. E.164 caps at fifteen digits; below
        // seven nothing is dialable, and both bounds exist to refuse a typo rather than to validate a
        // country.
        private val shape = Regex("""^\d{7,15}$""")

        fun parse(raw: String): Msisdn {
            val digits = raw.filter(Char::isDigit)
            if (!shape.matches(digits)) {
                // A Validation, so it answers 422 and names the field the screen highlights. It says
                // nothing about whether the number is known, because at this point nothing has
                // looked.
                throw KonektException.Validation("msisdn", "that does not look like a phone number")
            }
            return Msisdn(digits)
        }
    }
}
