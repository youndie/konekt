package io.konekt.di

import io.konekt.testing.productionSources
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The guard for a defect this repository actually shipped, and shipped quietly.
//
// The usage feature was built, tested and complete: counters, a repository, two use cases, a card
// builder, and a chain test that ran the whole thing against a real broker and a real Postgres. It
// was in the graph of nothing. `Application.kt` carried five imports of it and not one use, so a
// completed purchase granted no allowance and no route could read a counter. Every test passed,
// because each one assembled what it needed by hand — which is precisely what a test does.
//
// `KoinGraphTest` cannot see this: it verifies the modules it is GIVEN, so a module nobody installs
// is a module it is never given. What notices is the composition root itself, read as text.
class FeatureModulesReachTheGraphTest {
    private val compositionRoot = Path("src/main/kotlin/io/konekt/Application.kt")

    // `fun somethingModule(` at the top level of a feature's sources. Matched on the declaration
    // rather than on a list, so a feature added later is covered without anybody remembering.
    private val declaration = Regex("""^fun (\w+Module)\(""", RegexOption.MULTILINE)

    private fun featureModuleFunctions(): List<String> =
        productionSources()
            .filter { "feature" in it.toString() }
            .flatMap { declaration.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .distinct()
            .sorted()

    @Test
    fun `every feature's koin module is installed by the composition root`() {
        val declared = featureModuleFunctions()
        val root = compositionRoot.readText()

        val orphans = declared.filterNot { "$it(" in root }

        assertEquals(
            emptyList(),
            orphans,
            "these feature modules exist and nothing installs them, so everything behind them is " +
                "reachable only from a test:\n" + orphans.joinToString("\n"),
        )
    }

    @Test
    fun `the guard is looking at something`() {
        // Both halves, because either being empty makes the assertion above pass by vacuity: a
        // renamed convention would find no modules, and a moved file would find no composition root.
        val declared = featureModuleFunctions()
        assertTrue(declared.size >= 4, "found ${declared.size} feature modules — has the naming changed?")
        assertTrue(
            compositionRoot.readText().contains("fun Application.module("),
            "the composition root is not where this test looks for it",
        )
    }
}
