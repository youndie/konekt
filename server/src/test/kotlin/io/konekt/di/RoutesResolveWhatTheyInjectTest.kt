package io.konekt.di

import io.konekt.testing.productionSources
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The third shape of "written and never reached", and the one that cost the most to find.
//
// `FeatureModulesReachTheGraphTest` catches a module nothing installs. `WorkersAreStartedTest` catches
// a worker nothing starts. This catches a route that injects a type NOTHING BINDS — which is not a
// startup failure, because Koin resolves lazily: the application comes up, the health check passes,
// and the endpoint answers 500 the first time somebody asks for it.
//
// Two were found that way, by a stand: `LoadHistoryUseCase` and `LoadOrderScreenUseCase` were
// injected by `purchaseRoutes` and bound by nothing, so the history screen and the order screen were
// both 500 in the running server while 191 tests were green. Every route test builds its own graph
// and supplies what it needs, which is exactly why none of them could see it.
// `mappings` is marked @KoinInternalAPI. Opted into deliberately and with the trade named: the
// alternative is resolving every definition, which constructs a broker connection that opens a
// socket — a guard that needs a broker running is a guard that fails for the wrong reason. If a Koin
// upgrade moves this, the guard fails to COMPILE, which is the failure mode to prefer.
@OptIn(org.koin.core.annotation.KoinInternalApi::class)
class RoutesResolveWhatTheyInjectTest {
    private val injection = Regex("""\binject<([\w.]+)>\(\)""")

    private fun routingFiles() = productionSources().filter { it.fileName.toString().endsWith("Routing.kt") }

    // The simple name at an injection site plus the file's own import for it. Resolving the name
    // through the imports rather than guessing a package is what makes this work across features.
    private fun injectedTypes(): List<String> =
        routingFiles()
            .flatMap { file ->
                val text = file.readText()
                val imports =
                    text
                        .lineSequence()
                        .filter { it.startsWith("import ") }
                        .map { it.removePrefix("import ").trim() }
                        .associateBy { it.substringAfterLast('.') }

                injection.findAll(text).map { it.groupValues[1] }.map { name ->
                    imports[name] ?: name
                }
            }.distinct()
            .sorted()

    // The DEFINITIONS, read rather than resolved. Resolving would construct them, and one of the
    // things the application binds opens a socket to the broker in its constructor — a guard that
    // needs a broker running is a guard that fails for the wrong reason.
    private fun boundTypes(): Set<String> =
        KoinGraphTest.Modules.everything
            .flatMap { module -> module.mappings.keys }
            .toSet()

    @Test
    fun `every type a route injects is bound by the application's own modules`() {
        val bound = boundTypes()

        val unbound =
            injectedTypes().filterNot { fqn ->
                // Koin's index key carries the type name and, sometimes, a qualifier around it.
                bound.any { key -> key.contains(fqn) }
            }

        assertEquals(
            emptyList(),
            unbound,
            "these are injected by a route and cannot be resolved. Koin resolves lazily, so the " +
                "application starts, the health check passes, and the endpoint answers 500 the first " +
                "time somebody asks:\n" + unbound.joinToString("\n"),
        )
    }

    @Test
    fun `the guard is looking at something`() {
        // Both halves, because either being empty makes the assertion above pass by vacuity: a
        // renamed file convention finds no routes, and a changed injection idiom finds no types.
        assertTrue(routingFiles().size >= 4, "found ${routingFiles().size} routing files — has the naming changed?")
        assertTrue(injectedTypes().size >= 5, "found ${injectedTypes().size} injected types — has `by inject` gone?")
        assertTrue(boundTypes().size >= 20, "found ${boundTypes().size} bindings — is the module list assembled?")
    }
}
