package io.konekt.di

import io.konekt.time.KonektClock
import io.konekt.time.timeModule
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinApplication
import org.koin.test.verify.verify
import kotlin.test.Test
import kotlin.test.assertNotNull

// Koin resolves lazily, so a missing binding is a runtime failure at the first `by inject` — in a
// route, in production, under a user. Verifying the graph is what turns that into a test.
//
// It also catches the trap the reference conventions warn about: `singleOf(::XImpl)` resolves EVERY
// constructor parameter through the container, including ones with a Kotlin default value. The
// default is ignored, and a parameter whose type has no binding throws NoDefinitionFoundException at
// runtime while the compiler says nothing.
class KoinGraphTest {
    private val modules = listOf(timeModule)

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `every definition can be resolved`() {
        modules.forEach { it.verify() }
    }

    @Test
    fun `the graph really produces the bindings it claims`() {
        // `verify()` over an empty module list passes, so it is not by itself evidence of anything.
        // Resolving a binding by type is — per binding, and this list grows with the modules above,
        // which is what notices when a module reaches the application and not this test.
        val koin = koinApplication { modules(modules) }.koin

        assertNotNull(koin.get<KonektClock>(), "the clock is not in the graph")
    }
}
