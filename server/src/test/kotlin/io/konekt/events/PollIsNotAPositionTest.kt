package io.konekt.events

import io.konekt.testing.everyKotlinSource
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A POLL IS NOT A WAY TO LEARN A POSITION, and this build learned that four times over.
//
// `Consumer(…).let { it.poll(); it.position }` reads as "where is the end of the log". It is not.
// It is one `Consumer.DEFAULT_MAX_BYTES` in from the START, and those two numbers are the same only
// while the log is shorter than a single poll. `UsageChain` shipped it — offset 11915 against a log
// ending at 374473 on the stage deployment, so every restart replayed 362,558 events against live
// counters (`B-108`) — and three tests in this repository had copied the same shape.
//
// The behaviour is guarded where it matters: `TrafficChainTest` publishes before the chain starts and
// fails if the chain applies any of it. This is the second, cheaper guard, and it exists because the
// idiom SPREADS: it looks right, it works on a short log, and every one of the four instances was
// written by somebody who had just read another one.
//
// The answer, everywhere, is METADATA — `PartitionInfo.highWatermark`. It cannot read a record and it
// cannot be out of range.
class PollIsNotAPositionTest {
    // Every file allowed to contain the shape, and each is allowed for a reason that is checked
    // below rather than trusted.
    private val allowed =
        mapOf(
            "PollIsNotAPositionTest.kt" to "the guard necessarily spells what it forbids",
            "TrafficChainTest.kt" to
                "`B-108`'s own precondition measures the broken expression against the real end, " +
                "and has to spell it to do so",
        )

    // `.poll()` and a `position` within a few lines of it. Deliberately loose: the shape is written
    // with a `let`, with a temporary, over one line or four, and a matcher tight enough to name one
    // spelling would miss the next.
    private val shape = Regex("""\.poll\(\)[\s\S]{0,160}?\bposition\b""")

    private fun offenders() =
        everyKotlinSource()
            .filter { shape.containsMatchIn(it.readText()) }
            .associateBy { it.fileName.toString() }

    @Test
    fun `nothing reads a position out of a poll`() {
        val found = offenders()

        // VACUITY FIRST, and here it is not ceremony: this guard is a regex over a file walk, and
        // both halves can quietly come back empty — a walk rooted at the wrong directory, a regex
        // broken by an edit. With no subjects at all the assertion below passes for ever.
        assertTrue(
            everyKotlinSource().isNotEmpty(),
            "no Kotlin source was walked at all, so this guard is about nothing",
        )

        assertEquals(
            emptyList(),
            found.keys.filterNot { it in allowed }.sorted(),
            "these read a position out of a poll, which is one maxBytes in from the START of the " +
                "log and not the end of it (`B-108`). Ask METADATA for `highWatermark` instead",
        )
    }

    // AND EVERY EXEMPTION IS STILL EARNING IT. An allow-list is a hole in a guard, and the way a hole
    // widens is that the file it was cut for stops containing the thing and nobody removes the entry
    // — after which the next author of that file is unguarded and nothing says so.
    @Test
    fun `every exemption still contains the shape it was granted for`() {
        val found = offenders()

        assertEquals(
            emptyList(),
            allowed.keys.filterNot { it in found }.sorted(),
            "these are exempt from a rule they no longer break — remove the entry rather than " +
                "leaving the file unguarded: ${allowed.filterKeys { it !in found }}",
        )
    }
}
