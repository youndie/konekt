package io.konekt.di

import io.konekt.testing.productionSources
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The other half of the defect `FeatureModulesReachTheGraphTest` catches.
//
// That one finds a feature module nothing installs. This one finds a WORKER nothing starts, which is
// how `TrafficSimulator` and `UsageConsumer` spent a week fully written, covered end to end against
// a real broker, and constructed by nothing outside their own test. Every acceptance criterion on
// the item that produced them was about the chain being *tested*, and a chain that is tested and
// never started passes all of them.
//
// A binding is data and can be verified; a `start(scope)` call is control flow and cannot. So this
// reads the composition root as text, like its sibling.
class WorkersAreStartedTest {
    private val compositionRoot = Path("src/main/kotlin/io/konekt/Application.kt")

    // A class of OURS that takes a CoroutineScope and starts something in it. Matched on the
    // declaration rather than a list, so a worker added later is covered without anybody remembering.
    private val declaration = Regex("""(?m)^class (\w+)\(""")
    private val startsWork = Regex("""fun start\(\s*(?:\w+:\s*)?(?:scope: )?CoroutineScope""")

    private fun workerClasses(): List<String> =
        productionSources()
            .filter { it.toString().contains("/server/src/main/") }
            .mapNotNull { path ->
                val text = path.readText()
                if (!startsWork.containsMatchIn(text)) return@mapNotNull null
                declaration.find(text)?.groupValues?.get(1)
            }.distinct()
            .sorted()

    // Everything the composition root can reach BY NAME, closed over: the root names `TrafficChain`
    // and that class names the two workers it starts. One level would have been enough today and a
    // fixed point costs three lines, which is cheaper than the afternoon spent working out why a
    // started worker was being reported as orphaned.
    private fun reachableFromTheRoot(): String {
        val sources = productionSources().filter { it.toString().contains("/server/src/main/") }
        val text = StringBuilder(compositionRoot.readText())
        val taken = mutableSetOf<String>()

        do {
            val added =
                sources
                    .filter { it.fileName.toString() !in taken }
                    .filter { it.fileName.toString().removeSuffix(".kt") in text }
            added.forEach {
                taken += it.fileName.toString()
                text.append(it.readText())
            }
        } while (added.isNotEmpty())

        return text.toString()
    }

    @Test
    fun `every worker this server declares is started by the composition root`() {
        val declared = workerClasses()
        val root = reachableFromTheRoot()

        val neverStarted = declared.filterNot { it in root }

        assertEquals(
            emptyList(),
            neverStarted,
            "these start work on a scope and the application never constructs them, so they run " +
                "only in a test:\n" + neverStarted.joinToString("\n"),
        )
    }

    @Test
    fun `the guard is looking at something`() {
        // Either half being empty makes the assertion above pass by vacuity: a changed signature
        // would find no workers, a moved file no composition root.
        val declared = workerClasses()
        assertTrue(declared.isNotEmpty(), "no worker classes were found — has the start signature changed?")
        assertTrue(
            compositionRoot.readText().contains("ApplicationStarted"),
            "the composition root does not start anything where this test looks",
        )
    }
}
