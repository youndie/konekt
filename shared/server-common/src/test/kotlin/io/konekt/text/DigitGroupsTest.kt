package io.konekt.text

import kotlin.test.Test
import kotlin.test.assertEquals

class DigitGroupsTest {
    // THE IMPLEMENTATION THIS REPLACED, kept as the oracle rather than as a memory of what it did.
    // A hand-written table of expectations tests my reading of the old code; this tests the old code.
    private fun chain(
        value: Long,
        separator: Char,
    ): String =
        value
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(separator.toString())
            .reversed()

    @Test
    fun `grouping starts above a thousand and not below`() {
        assertEquals("999", DigitGroups.grouped(999, ','))
        assertEquals("1,000", DigitGroups.grouped(1_000, ','))
        assertEquals("0", DigitGroups.grouped(0, ','))
    }

    @Test
    fun `the first group is the short one`() {
        assertEquals("1,234,567", DigitGroups.grouped(1_234_567, ','))
        assertEquals("123,456", DigitGroups.grouped(123_456, ','))
        assertEquals("12,345", DigitGroups.grouped(12_345, ','))
    }

    @Test
    fun `the separator is the currency's own`() {
        assertEquals("1 234 567", DigitGroups.grouped(1_234_567, ' '))
    }

    @Test
    fun `a negative carries its sign and is not grouped by it`() {
        assertEquals("-1,234", DigitGroups.grouped(-1_234, ','))
        assertEquals("-999", DigitGroups.grouped(-999, ','))
    }

    @Test
    fun `the two implementations agree on every value a caller can pass`() {
        // Every boundary the loop can have — the lengths where the lead group changes — plus the
        // top of the type and a stride through the middle. NON-NEGATIVE, which is the whole domain
        // the callers have: `MoneyFormat` takes the absolute value before it groups and a usage
        // counter cannot be below zero. The negative case is the test below, and it is the reason
        // this one says "a caller can pass" rather than "in the range".
        val values =
            buildList {
                addAll(0L..2_000L)
                var magnitude = 1L
                repeat(18) {
                    add(magnitude - 1)
                    add(magnitude)
                    add(magnitude + 1)
                    magnitude *= 10
                }
                addAll(generateSequence(7L) { it * 37 }.takeWhile { it < Long.MAX_VALUE / 37 })
                add(Long.MAX_VALUE)
            }

        values.forEach { value ->
            assertEquals(chain(value, ','), DigitGroups.grouped(value, ','), "grouping $value")
        }
    }

    @Test
    fun `the chain this replaced mangled a negative whose digits fill their groups`() {
        // FOUND BY THE ORACLE, not by reading: the first version of the test above ran both signs
        // and failed on -100. Reversed, "-100" is "001-", which `chunked(3)` splits into "001" and
        // "-" — so the minus becomes a group of its own and comes back as "-,100". It happens
        // whenever the digit count is a multiple of three: -100, -100000.
        //
        // No caller could reach it (both group an absolute value or a counter), so this was never a
        // defect anybody could see — which is exactly why it survived. Kept as a test because the
        // one-pass form is now the only implementation, and this says what it must not go back to.
        assertEquals("-,100", chain(-100, ','))
        assertEquals("-100", DigitGroups.grouped(-100, ','))
        assertEquals("-,100,000", chain(-100_000, ','))
        assertEquals("-100,000", DigitGroups.grouped(-100_000, ','))

        // The lengths the chain did get right, kept so the new form is pinned on both sides.
        assertEquals("-1,234", DigitGroups.grouped(-1_234, ','))
        assertEquals(chain(-1_234, ','), DigitGroups.grouped(-1_234, ','))
    }
}
