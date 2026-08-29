package io.konekt.feature.usage.server.data

import io.konekt.components.CounterStates
import io.konekt.feature.usage.server.domain.UsageCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// The copy review, in the same place as the code.
//
// The canvas's rule for this card is that the LOW STATE CHANGES THE WORDS and not only the colour —
// "Minutes run out in about two days at your current pace. A 100-minute add-on costs $4." Every part
// of that sentence is something somebody could later simplify away: the projection, the hedge in
// "about", and the price that turns a warning into an offer.
class UsageCounterCardsTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

    // THE INSTANT IS AN ARGUMENT NOW (`B-96`), not a clock the factory holds: a screen draws
    // several of these and reads the time once for all of them.
    private val cards = UsageCounterCards(StaticUsageAddOns())

    private fun counter(
        kind: UsageCounter.Kind,
        limit: Long,
        remaining: Long,
    ) = UsageCounter(
        subscriberId = "sub-1",
        kind = kind,
        limitUnits = limit,
        remainingUnits = remaining,
        startedAt = start,
    )

    @Test
    fun `an ordinary counter says nothing extra`() {
        val card = cards.of(counter(UsageCounter.Kind.DATA, 10_240, 8_000), start + 1.days)

        assertEquals(CounterStates.NORMAL, card.state)
        // A caption in the ordinary state is a caption nobody reads by the time it matters.
        assertNull(card.captionText)
        assertEquals("7.8 GB left", card.valueText)
    }

    @Test
    fun `the low state projects when it runs out and what it costs to fix`() {
        // EIGHTEEN days, and the number is arithmetic rather than taste. The rate is a mean over the
        // whole allowance, so a counter with a tenth left has a ninth of its elapsed life ahead of
        // it: 900 minutes over 18 days is 50 a day, and 100 left is two days more. Two days out of a
        // thirty-day plan is exactly the situation the canvas draws — and a shorter window cannot
        // produce it, which is worth knowing before someone "simplifies" the fixture.
        val card = cards.of(counter(UsageCounter.Kind.MINUTES, 1_000, 100), start + 18.days)

        assertEquals(CounterStates.LOW, card.state)
        assertEquals(
            "Minutes run out in about two days at your current pace. A 100-minute add-on costs $4.",
            card.captionText,
        )
    }

    @Test
    fun `the verb follows the noun`() {
        val data = cards.of(counter(UsageCounter.Kind.DATA, 10_240, 1_000), start + 2.days)

        // "Data runs out", not "Data run out". A machine-written screen is what a backend-driven
        // product has to work hardest not to read like.
        assertTrue(data.captionText!!.startsWith("Data runs out"), data.captionText!!)
    }

    @Test
    fun `a counter measured before any time has passed does not project`() {
        val card = cards.of(counter(UsageCounter.Kind.MINUTES, 1_000, 50), start)

        // Same instant it started: no elapsed time, so no rate. The card falls back to the fact.
        assertEquals(
            "Running low — under a tenth of your minutes is left. A 100-minute add-on costs \$4.",
            card.captionText,
        )
    }

    @Test
    fun `an exhausted counter says so plainly and still offers the way out`() {
        val card = cards.of(counter(UsageCounter.Kind.MESSAGES, 200, 0), start + 3.days)

        assertEquals(CounterStates.EXHAUSTED, card.state)
        assertEquals(
            "You have used all of your messages. A 200-message add-on costs \$2.",
            card.captionText,
        )
        assertEquals("0 SMS left", card.valueText)
    }

    @Test
    fun `the id is derived from the counter so a live update can name it`() {
        // Not decoration: `UpdateComponentMessage(componentId, component)` finds the node by this id
        // in a tree the client already has. A generated id would replace nothing, silently.
        assertEquals("counter-data", UsageCounterCards.idOf(counter(UsageCounter.Kind.DATA, 1, 1)))
    }

    @Test
    fun `data crosses into gigabytes and money-style grouping is used below it`() {
        val small = cards.of(counter(UsageCounter.Kind.DATA, 2_000, 512), start)
        assertEquals("512 MB left", small.valueText)

        val large = cards.of(counter(UsageCounter.Kind.DATA, 20_480, 20_480), start)
        // A whole number drops its zero fraction, the same rule MoneyFormat follows.
        assertEquals("20 GB left", large.valueText)
    }
}
