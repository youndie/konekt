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
    //
    // `Screen.page` is in the list because leaving it out cost a 500 in production code. A page
    // response is not a KompotComponent and so escaped the wording of this rule — but its `items`
    // ARE components, so a plain respond serialises them through ContentNegotiation's Json, which
    // carries none of this build's dictionary. The rule was never about the root type; it is about
    // anything that has konekt's components inside it.
    private val suspicious =
        Regex("""\bcall\.respond\s*\(\s*[^)]*(Screen\.build|Screen\.page|Component\(|Cards\.of|cards\.of)""")

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
    fun `every tree that is built is encoded by this build's own Json, at the call site`() {
        // The other direction, and the one that matters more: the check above passes on a repository
        // with no screens in it at all.
        //
        // PER CALL SITE, and that is the correction rather than a refinement. This asked whether the
        // FILE mentioned respondKompotComponent, and `PurchaseRouting.kt` did — for its two screen
        // routes — while the page route beside them went out through a plain respond and answered
        // 500 for every client that scrolled. A guard written over the shape it first saw is blind to
        // the neighbouring case of the same thing.
        val builders = Regex("""(Screen\.build|Screen\.page|cards\.of)\(""")

        // The two correct ways to put this build's components on the wire: the toolkit's helper for a
        // component tree, and an explicit encode with the injected `json` for anything the toolkit
        // has no helper for. Both name the Json; ContentNegotiation's does not appear in either.
        val encoded = Regex("""(respondKompotComponent|json\.encodeToString)""")

        // A WINDOW AND NOT A LINE. `respondKompotComponent(` and its argument are on separate lines
        // whenever the argument list is long enough for the formatter to break it, and a per-line
        // check calls those correct call sites offenders. Three lines back is what covers a broken
        // argument list without reaching the previous statement.
        val lookBack = 3

        val sites =
            sources()
                .filter { "routing" in it.toString().lowercase() }
                .flatMap { path ->
                    val lines = path.readText().lines()
                    lines.withIndex().mapNotNull { (index, line) ->
                        if (!builders.containsMatchIn(line)) {
                            null
                        } else {
                            val window = lines.subList(maxOf(0, index - lookBack), index + 1).joinToString("\n")
                            Triple(path.toString(), index + 1, line.trim()) to window
                        }
                    }
                }

        // The guard on the guard: a regex that stopped matching would make this vacuous.
        assertTrue(sites.isNotEmpty(), "no screen builder call was found in any routing file — is the pattern right?")

        val unencoded = sites.filterNot { (_, window) -> encoded.containsMatchIn(window) }.map { it.first }

        assertEquals(
            emptyList(),
            unencoded.map { (path, line, text) -> "$path:$line  $text" },
            "these build a tree and do not name the Json that encodes it, so it goes out through " +
                "ContentNegotiation's — which has none of this build's components in its polymorphic scope:\n" +
                unencoded.joinToString("\n") { (path, line, text) -> "$path:$line  $text" },
        )
    }
}
