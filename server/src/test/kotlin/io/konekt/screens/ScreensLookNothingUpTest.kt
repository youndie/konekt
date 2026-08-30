package io.konekt.screens

import io.konekt.testing.everyKotlinSource
import io.konekt.testing.productionSources
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// `B-96`'S RULE, AND IT IS THE ONLY PART OF THAT ITEM A TYPE CANNOT ENFORCE: a screen renders what it
// is given and looks nothing up.
//
// The rule is the LOOKUP and not the number of parameters. `PlansScreen.build(plans: List<Plan>)`
// takes a domain list and decides nothing, and wrapping it in a view would be a hop that buys
// nothing; `HomeScreen` taking a `UsageCounterCards` is fine because that is a RENDERER, which is
// this side of the line. What is forbidden is a screen that can ask a question — of a repository, of
// a catalogue, of a use case, or of the clock.
//
// WHY IT MATTERS, in one sentence per half. A renderer that can look something up can answer a
// different question than the use case answered, which is how one response comes to disagree with
// itself. And a decision made inside a renderer is a decision testable only by building a component
// tree and filtering it by a string id — so a renamed id stops finding anything and the assertion
// quietly becomes about `null`, which is the vacuity `B-84` found four times in one file.
class ScreensLookNothingUpTest {
    // A screen file by its name, which is the convention every one of them follows. Not by what is
    // inside it: a file that stopped drawing components would stop being checked at exactly the
    // moment it started doing something else.
    private fun screenSources() =
        productionSources().filter { it.name.endsWith("Screen.kt") || it.name.endsWith("Screens.kt") }

    // WHAT A SCREEN MAY NOT REACH FOR. Matched on the import rather than on the use, because an
    // import is where the reach is declared and a use can be spelled a dozen ways.
    //
    // `Cards` is deliberately absent: a card factory turns a counter into a component and is a
    // renderer, so a screen may hold one. What made those factories dangerous was the CLOCK inside
    // them, and the clock is on this list.
    private val forbidden =
        listOf(
            Regex("""^import .*\.\w*Repository$"""),
            Regex("""^import .*\.\w*Repositories$"""),
            Regex("""^import .*\.\w*Catalogue$"""),
            Regex("""^import .*\.\w*Catalog$"""),
            Regex("""^import .*\.\w*UseCase$"""),
            Regex("""^import .*\.KonektClock$"""),
        )

    @Test
    fun `no screen imports something it could ask a question of`() {
        val screens = screenSources()
        // A FLOOR ON THE INPUT. An empty walk passes every assertion below it, and a guard that
        // passes by finding nothing is the failure mode this repository has now met twice.
        assertTrue(screens.size >= 8, "only ${screens.size} screen files were found, so this checked almost nothing")

        val offenders =
            screens.flatMap { path ->
                path
                    .readText()
                    .lines()
                    .filter { line -> forbidden.any { it.matches(line.trim()) } }
                    .map { "${path.name}: ${it.trim()}" }
            }

        assertEquals(
            emptyList(),
            offenders,
            "a screen reaches for something it can ask a question of; the answer belongs in its view " +
                "(B-96):\n" + offenders.joinToString("\n"),
        )
    }

    // THE OTHER HALF, and the one an import list cannot state: a screen that took the clock as an
    // argument rather than as a field would pass the check above and read the time per card again.
    @Test
    fun `no screen reads the time while drawing`() {
        val offenders =
            screenSources()
                .filter { it.readText().contains(".now()") }
                .map { it.name }

        assertEquals(
            emptyList(),
            offenders,
            "a screen reads the clock while drawing, so two parts of one response can disagree about " +
                "the time; take the instant from the view (B-96):\n" + offenders.joinToString("\n"),
        )
    }

    // A VIEW IS SERVER-INTERNAL. The wire is the component tree, and a view type in a `*-shared-api`
    // module would make the client depend on how the server split its presentation — which is the one
    // thing this whole arrangement exists to avoid.
    //
    // Over EVERY module rather than over the ones that have views today: the point is the module a
    // view must never appear in, and the next one added is the one nobody would think to check.
    @Test
    fun `no view type is declared on the wire`() {
        val declaration = Regex("""^(data )?class (\w*View)\b""")

        val offenders =
            everyKotlinSource()
                .filter { path -> path.any { it.name.endsWith("-shared-api") } }
                .flatMap { path ->
                    path
                        .readText()
                        .lines()
                        .mapNotNull { declaration.find(it.trim())?.groupValues?.get(2) }
                        .map { "${path.name}: $it" }
                }

        assertEquals(
            emptyList(),
            offenders,
            "a view type is declared in a shared-api module, so the client now depends on how the " +
                "server split its presentation (B-96):\n" + offenders.joinToString("\n"),
        )
    }
}
