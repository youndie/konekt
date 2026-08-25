package io.konekt.openapi

import io.konekt.testing.productionSources
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The other half of the OpenAPI guard, and the half a JSON comparison cannot supply.
//
// `OpenApiDocumentTest` proves that the document agrees with the routing tree the ROUTE TABLE
// produces. It cannot prove that the table is the whole of the routing: a route registered directly
// inside `routing { }` in the composition root would serve happily in production and never appear in
// any tree the generator builds — the same shape as the usage feature that was complete, tested, and
// installed by nothing.
//
// A binding is data and can be verified; a call is control flow and cannot. So this reads the file,
// exactly as `FeatureModulesReachTheGraphTest` and `WorkersAreStartedTest` do.
class CompositionRootRoutesTest {
    private val compositionRoot = Path("src/main/kotlin/io/konekt/Application.kt")

    // `somethingRoutes(` — the naming every feature's route function follows. Matched on the shape
    // rather than on a list, so a feature added later is covered without anybody remembering.
    private val routeCall = Regex("""\b(\w*[Rr]outes)\(""")

    // The body of `fun Application.module(...)`, up to the next top-level declaration. Taken as text
    // because that is the only way to ask this question at all.
    private fun compositionRootModule(): String {
        val text = compositionRoot.readText()
        val start = text.indexOf("fun Application.module(")
        require(start >= 0) { "the composition root is not where this test looks for it" }
        val end = text.indexOf("\nfun ", start + 1).takeIf { it > 0 } ?: text.length
        return text.substring(start, end)
    }

    private fun routingBlocks(module: String): List<String> {
        val blocks = mutableListOf<String>()
        var from = 0
        while (true) {
            val open = module.indexOf("routing {", from)
            if (open < 0) return blocks
            // Balanced braces from the block's own opening one, so a nested `authenticate { }` does
            // not end the block early.
            var depth = 0
            var index = module.indexOf('{', open)
            val begin = index
            while (index < module.length) {
                when (module[index]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                if (depth == 0) break
                index++
            }
            blocks += module.substring(begin, minOf(index + 1, module.length))
            from = index + 1
        }
    }

    @Test
    fun `the composition root mounts the route table and registers no route of its own`() {
        val blocks = routingBlocks(compositionRootModule())

        val calls = blocks.flatMap { routeCall.findAll(it).map { match -> match.groupValues[1] } }.distinct().sorted()

        assertEquals(
            listOf("mountKonektRoutes"),
            calls,
            "the composition root registers routes outside the table in Application.kt. Every one of " +
                "these serves in production and appears in no OpenAPI document, because the generator " +
                "mounts the table and nothing else:\n" + calls.joinToString("\n"),
        )
    }

    @Test
    fun `the guard is looking at something`() {
        // Three ways this could pass by finding nothing: a moved file, a renamed composition root, a
        // `routing { }` block that is no longer spelled that way. Each of them would make the
        // assertion above vacuous, and each of them is a plausible refactor.
        val module = compositionRootModule()
        assertTrue(module.contains("routing {"), "no routing block in the composition root — has it moved?")
        assertEquals(
            1,
            routingBlocks(module).size,
            "the composition root has more than one routing block; this guard reads all of them, but a " +
                "second one is itself worth a look",
        )
        // And the fourth: the pattern could stop describing the convention it is written for. Held
        // against the feature route functions themselves rather than against the composition root —
        // matching `mountKonektRoutes(` there would be the guard checking its own reflection.
        val declared =
            productionSources()
                .filter { it.fileName.toString().endsWith("Routing.kt") }
                .flatMap { routeCall.findAll(it.readText()).map { match -> match.groupValues[1] } }
                .distinct()
        assertTrue(
            declared.size >= 6,
            "the pattern matches only ${declared.size} route functions across the repository — has the " +
                "`somethingRoutes()` convention changed? Then this guard matches nothing and passes always",
        )
    }
}
