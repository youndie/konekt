package io.konekt.http

import io.konekt.testing.productionSources
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The half of B-07 no type can enforce: that nobody answers a component tree with `call.respond`.
//
// The failure it prevents is the worst-behaved one in this repository. A plain respond resolves the
// serialiser from the CONCRETE runtime class, so the root of the tree goes out without its `"type"`
// discriminator while every nested child serialises perfectly. The client then receives an unknown
// component for the whole screen and — by design, since an unknown component degrades — draws
// nothing. No exception, no log, a 200, and a blank screen.
//
// It reads the source because there is no signature to forbid: `call.respond(anything)` compiles.
class CallRespondUsageTest {
    // Any `respond` whose argument mentions a screen builder or a component type. Matching the
    // ARGUMENT rather than the call is what makes it specific: `call.respond(order.toResponse())` is
    // correct and must stay correct.
    private val suspicious =
        Regex("""\bcall\.respond\s*\(\s*[^)]*(Screen\.build|Component\(|Cards\.of|cards\.of)""")

    private fun sources() = productionSources()

    @Test
    fun `no route answers a component tree with a plain respond`() {
        val offenders =
            sources()
                .filter { suspicious.containsMatchIn(it.readText()) }
                .map { it.toString() }

        assertEquals(
            emptyList(),
            offenders,
            "these answer a KompotComponent with call.respond, which drops the root's type " +
                "discriminator and leaves the client drawing nothing:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `every screen that is built is answered with respondKompotComponent`() {
        // The other direction, and the one that matters more: the check above passes on a repository
        // with no screens in it at all. This one counts the calls that DO build a tree and asserts
        // each sits inside a `respondKompotComponent`, so a route added without one fails here
        // rather than in a client that draws a blank.
        val builders = Regex("""(Screen\.build|Screen\.page|cards\.of)\(""")
        val correct = Regex("""respondKompotComponent""")

        val files =
            sources()
                .map { it to it.readText() }
                .filter { (_, text) -> builders.containsMatchIn(text) }

        // The guard on the guard: a regex that stopped matching would make both assertions vacuous.
        assertTrue(files.isNotEmpty(), "no screen builder call was found anywhere — is the pattern right?")

        val unanswered =
            files
                .filter { (path, text) -> "routing" in path.toString().lowercase() && !correct.containsMatchIn(text) }
                .map { it.first.toString() }

        assertEquals(
            emptyList(),
            unanswered,
            "these build a screen in a routing file and never call respondKompotComponent:\n" +
                unanswered.joinToString("\n"),
        )
    }
}
