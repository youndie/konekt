package io.konekt.http

import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The half of B-34 no type can enforce: that nobody reaches for plain `runCatching` in suspending
// code.
//
// It compiles, it reads exactly like the right thing, and it swallows cancellation — after which a
// request that a client abandoned keeps running to completion and a `withTimeout` around it stops
// nothing. There is no signature to forbid and no warning to switch on, so the guard reads the
// source, the same way ClockUsageTest does.
//
// It is coarse on purpose: `runCatching` in non-suspending code is fine, and this refuses it anyway.
// The alternative is parsing Kotlin to find out which is which, and a rule that is slightly too
// strict and always right beats a rule that is exact and sometimes silent.
class RunCatchingUsageTest {
    private val roots =
        listOf(
            "src/main/kotlin",
            "../shared/domain/src/commonMain/kotlin",
            "../shared/components/src/commonMain/kotlin",
        )

    // The file that defines the replacement necessarily mentions the thing it replaces.
    private val allowed = setOf("SuspendRunCatching.kt")

    private val forbidden = Regex("""\brunCatching\s*[({]""")

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun sources() = io.konekt.testing.productionSources()

    @Test
    fun `nothing uses plain runCatching`() {
        val offenders =
            sources()
                .filter { it.fileName.toString() !in allowed }
                .filter { forbidden.containsMatchIn(it.readText()) }
                .map { it.toString() }

        assertEquals(
            emptyList(),
            offenders,
            "these use runCatching instead of suspendRunCatching, which swallows cancellation:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the guard is reading the sources it thinks it is`() {
        // Both assertions above pass on an empty file list, and a moved directory produces exactly
        // that. The roots are relative to the module, so a change of test working directory would
        // silently empty this guard.
        val files = sources()
        assertTrue(files.size >= 10, "found ${files.size} production source files — are the paths right?")
        assertTrue(
            files.any { it.fileName.toString() == "SuspendRunCatching.kt" },
            "the shared domain is not in the scanned set, so the rule it defines is unguarded",
        )
    }
}
