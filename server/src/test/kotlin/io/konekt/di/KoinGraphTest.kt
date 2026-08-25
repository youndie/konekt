package io.konekt.di

import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.auth.server.data.authModule
import io.konekt.feature.purchase.server.data.purchaseModule
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
    // `verify()` never opens a connection, and the definitions that capture this never run. Naming it
    // rather than passing a mock is the honest form: a mock here would suggest the graph is being
    // exercised, and it is not — only its constructors are.
    private companion object {
        val NO_DATABASE: org.jetbrains.exposed.v1.jdbc.Database =
            org.jetbrains.exposed.v1.jdbc.Database
                .connect({ error("the graph verifier never opens a connection") })
    }

    // Every module the composition root installs, not just the small one. `verify()` inspects
    // constructors rather than building anything, so a module needing a Database can be verified
    // without one — which is what makes covering the features here cheap enough to be worth doing.
    private val modules =
        listOf(
            timeModule,
            authModule(NO_DATABASE, JwtConfig("s", "i", "a"), revealCodes = false),
            purchaseModule(NO_DATABASE),
        )

    // Types a module takes and a DIFFERENT module provides. `verify()` inspects one module at a time,
    // so without this list every cross-module dependency reads as missing — and with it, a
    // dependency on a type NOTHING provides still fails, which is the check worth having.
    //
    // Each entry is here because the composition root really does bind it, and the list is short on
    // purpose: it grows only when a feature genuinely reaches across, which is a thing worth noticing
    // rather than waving through.
    private val providedByTheRoot =
        listOf(
            // timeModule
            KonektClock::class,
            // the application's single Json, assembled in Application.kt
            kotlinx.serialization.json.Json::class,
            // petichModule, which needs a live Database and so is not verified here
            ru.workinprogress.petich.PetichEngine::class,
            ru.workinprogress.petich.PetichRepository::class,
        )

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `every definition can be resolved`() {
        modules.forEach { it.verify(extraTypes = providedByTheRoot) }
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
