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
        // DROPPED BEFORE THE WALK, not after it, and the difference matters as much as the rules
        // themselves. Dropping them afterwards fixes nothing: an import or a binding pulls the
        // worker's own file into the closure, and that file contains `class UsageChain(` — so the
        // name is in the accumulated text whatever is done to the root's lines later.
        val text = StringBuilder(compositionRoot.readText().replace(mentionsThatAreNotUses, ""))
        // THE ROOT IS ALREADY IN, AND MUST NOT BE ADDED AGAIN. Its first `^class …(` is `RouteGroup`,
        // which its own text names — so the walk below re-appended `Application.kt` verbatim and
        // undid the exclusion above, imports and bindings and all. That is how a deleted start call
        // survived three attempts to make this guard notice it.
        val taken = mutableSetOf(compositionRoot.fileName.toString())

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

    // AN IMPORT IS NOT A USE AND A BINDING IS NOT A START, and these two lines are the whole guard.
    //
    // Without them this test was a search for a class NAME in the composition root, and every worker
    // the root resolves is named there twice before anything starts it: once by its import and once
    // by its `single { }`. `B-89` measured it — the start call for `UsageChain` was deleted and the
    // test stayed green. What it actually caught was a worker mentioned NOWHERE, which is a narrower
    // failure than the one it is named for.
    //
    // Both rules are narrow and true. An `import` says a file can name a type; a `single { }` says a
    // worker can be BUILT; this test is about whether anything ever runs one.
    private val mentionsThatAreNotUses = Regex("""(?m)^\s*(import .*|(single|factory)(Of)?\s*[({<].*)$""")

    // A NAME IS NOT A START EITHER, which is the third thing this guard had to learn.
    //
    // With imports and bindings excluded the search was still for a class NAME, and a COMMENT
    // satisfied it: `TrafficChain` mentions `UsageChain` in a sentence explaining the split, and
    // `TrafficChain` is reachable, so the name was present and the deleted start call went unnoticed
    // a third time. Measured, not reasoned about — the start call was removed and the test stayed
    // green through two rounds of fixing it.
    //
    // So this matches a CALL, in the two shapes this build uses: resolved from the container and
    // started, or constructed and started. The second is bounded by "with no other `.start(` in
    // between", so a construction cannot borrow the start call of whatever follows it.
    private fun started(
        text: String,
        worker: String,
    ): Boolean {
        val flat = text.replace(Regex("""\s+"""), " ")
        val resolved = Regex("""get<$worker>\(\)\s*\.start\(""")
        // `(?<!class )` because a DECLARATION looks exactly like a construction once the file is
        // flattened: `class UsageChain(` matched, and the lazy tail then ran forward to the
        // `.start(` of a DIFFERENT worker further down the same file. That is the fourth way this
        // guard found to pass over a worker nothing starts, and the last one measured.
        val constructed = Regex("""(?<!class )\b$worker\s*\((?:(?!\.start\().)*?\.start\(""")
        return resolved.containsMatchIn(flat) || constructed.containsMatchIn(flat)
    }

    @Test
    fun `every worker this server declares is started by the composition root`() {
        val declared = workerClasses()
        val root = reachableFromTheRoot()

        val neverStarted = declared.filterNot { started(root, it) }

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
        // And the binding lines really are being dropped, or the rule above is decorative. A root
        // with no `single { }` in it is a root this test has stopped reading.
        assertTrue(
            mentionsThatAreNotUses.containsMatchIn(compositionRoot.readText()),
            "no imports or bindings were found to exclude, so the two rules above check nothing",
        )
        assertTrue(
            compositionRoot.readText().contains("ApplicationStarted"),
            "the composition root does not start anything where this test looks",
        )
    }
}
