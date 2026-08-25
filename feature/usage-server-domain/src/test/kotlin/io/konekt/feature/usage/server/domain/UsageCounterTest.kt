package io.konekt.feature.usage.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// The projection on its own, including the two ways it refuses to answer — which is the half a card
// test cannot reach, because a counter that has spent nothing is never in the low state that shows
// the sentence.
class UsageCounterTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun counter(
        limit: Long,
        remaining: Long,
    ) = UsageCounter("sub-1", UsageCounter.Kind.DATA, limit, remaining, startedAt = start)

    @Test
    fun `the rate is what has been spent over the whole life of the allowance`() {
        // 900 of 1000 spent in 18 days is 50 a day; 100 left is two days.
        val days = counter(1_000, 100).daysRemaining(start + 18.days)

        assertTrue(days != null && days > 1.9 && days < 2.1, "got $days")
    }

    @Test
    fun `nothing spent means no answer rather than an infinite one`() {
        // The arithmetic here is a division by zero, and the honest result is "cannot say" rather
        // than a very large number that a caption would round into a promise.
        assertNull(counter(1_000, 1_000).daysRemaining(start + 5.days))
    }

    @Test
    fun `no elapsed time means no answer rather than zero`() {
        // Granted this instant. Zero would be read as "runs out today", which is the opposite of
        // what is true about an allowance that has just arrived.
        assertNull(counter(1_000, 100).daysRemaining(start))
    }

    @Test
    fun `an exhausted counter has nothing left to project`() {
        assertNull(counter(1_000, 0).daysRemaining(start + 5.days))
    }

    @Test
    fun `low is a tenth, and the boundary is on the low side`() {
        // Both sides, because a comparison written the wrong way round calls a counter low one unit
        // early or one unit late and reads identically either way.
        assertTrue(counter(1_000, 100).isLow, "exactly a tenth is low")
        assertEquals(false, counter(1_000, 101).isLow)
    }
}
