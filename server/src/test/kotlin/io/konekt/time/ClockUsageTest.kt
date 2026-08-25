package io.konekt.time

import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The half of B-33 no type can enforce: that nobody goes around the injected clock.
//
// A `Clock.System.now()` at a call site compiles, passes review and works — and quietly makes the
// rule that contains it untestable without waiting. There is no signature to forbid it and no
// warning to enable, so the guard reads the source.
//
// It allows exactly one file, by name. An allowance by directory would grow to cover whatever moved
// into that directory next.
class ClockUsageTest {
    private val allowed = setOf("KonektClock.kt")

    private val forbidden = Regex("""\bClock\.System\b""")

    // Every module's production sources, discovered rather than listed: a module added later is
    // covered without anybody remembering to add it here, which is the failure mode of a list.
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun sources() = io.konekt.testing.productionSources()

    @Test
    fun `only the clock implementation reads the system clock`() {
        val offenders =
            sources()
                .filter { it.fileName.toString() !in allowed }
                .filter { forbidden.containsMatchIn(it.readText()) }
                .map { it.toString() }

        assertEquals(
            emptyList(),
            offenders,
            "these read the system clock directly instead of taking a KonektClock:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the allowance names a file that exists and actually uses it`() {
        // The guard on the guard, in both directions. A renamed implementation would leave the
        // allowance covering nothing — and the first assertion would still pass, having found no
        // offenders because it found nothing at all.
        val files = sources()
        assertTrue(files.size >= 10, "found ${files.size} source files — is the path right?")

        allowed.forEach { name ->
            val file = files.singleOrNull { it.fileName.toString() == name }
            assertTrue(file != null, "$name is allowed to read the system clock but does not exist")
            assertTrue(
                forbidden.containsMatchIn(file.readText()),
                "$name no longer reads the system clock, so its allowance should go",
            )
        }
    }
}
