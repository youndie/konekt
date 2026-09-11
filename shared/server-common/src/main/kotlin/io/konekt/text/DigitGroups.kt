package io.konekt.text

// Thousands separated, in one pass.
//
// ONE HOME FOR IT, and that is the first reason this file exists: the money formatter and the usage
// formatter each had their own copy of `reversed().chunked(3).joinToString(sep).reversed()`, with a
// comment in the second saying it matches the first. Two copies of a rule about how a number is
// written are two rules that agree today.
//
// ONE PASS, and that is the second. The chain above allocates a StringBuilder for the first
// `reversed`, a list plus one String per group for `chunked`, a StringBuilder and a String for
// `joinToString`, and another pair for the second `reversed` — ten or so objects to put two commas
// into a number. sborka's perf-lint names this shape (`kapkan`, "two or more eager materialisations
// in one body"), and it named these two methods because an allocation profile of this service
// charged them 1.6 % of every byte it allocated — the largest single owner in `io.konekt` was
// `MoneyFormat.group`. See sborka `docs/research/research-perf-lint.md`.
//
// The sign is carried rather than grouped: `(-1234).toString()` is `-1234`, and a `-` is not a
// digit. The chain this replaces got that WRONG and nobody could see it — reversed, "-100" is
// "001-", so `chunked(3)` made the minus a group and gave back "-,100" for every negative whose
// digit count is a multiple of three. No caller can reach it (money groups an absolute value, a
// usage counter cannot be negative), which is why it survived; the test holds both forms against
// each other and records the difference rather than averaging it away.
object DigitGroups {
    private const val GROUP = 3

    fun grouped(
        value: Long,
        separator: Char,
    ): String {
        val digits = value.toString()
        val start = if (digits[0] == '-') 1 else 0
        val count = digits.length - start
        if (count <= GROUP) return digits

        val separators = (count - 1) / GROUP
        val out = StringBuilder(digits.length + separators)
        if (start == 1) out.append('-')

        // The first group is the short one: 1 234 567 leads with one digit, 123 456 with three.
        val lead = (count - 1) % GROUP + 1
        out.append(digits, start, start + lead)
        var i = start + lead
        while (i < digits.length) {
            out.append(separator).append(digits, i, i + GROUP)
            i += GROUP
        }
        return out.toString()
    }
}
