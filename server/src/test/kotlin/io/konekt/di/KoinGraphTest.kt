package io.konekt.di

import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.auth.server.data.authModule
import io.konekt.feature.esim.server.data.esimModule
import io.konekt.feature.esim.server.domain.SmDpPlus
import io.konekt.feature.purchase.server.data.purchaseModule
import io.konekt.feature.usage.server.data.usageModule
import io.konekt.feature.usage.server.domain.UsageCounters
import io.konekt.observability.KonektTrace
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
    // Not private: RoutesResolveWhatTheyInjectTest asks a different question of the same graph —
    // this file checks that every definition CAN be built, and that one checks that everything a
    // route asks for HAS one.
    companion object {
        val NO_DATABASE: org.jetbrains.exposed.v1.jdbc.Database =
            org.jetbrains.exposed.v1.jdbc.Database
                .connect({ error("the graph verifier never opens a connection") })

        fun applicationGraph() = koinApplication { modules(Modules.all) }.koin
    }

    // Every module the composition root installs, not just the small one. `verify()` inspects
    // constructors rather than building anything, so a module needing a Database can be verified
    // without one — which is what makes covering the features here cheap enough to be worth doing.
    private val modules get() = Modules.all

    // In an object so both this test and the injection guard install exactly what the application
    // installs, including the composition root's own module — an inline `module { }` in
    // Application.kt is invisible from here, and every binding inside one is a binding only a running
    // process has ever resolved.
    object Modules {
        val all =
            listOf(
                timeModule,
                authModule(NO_DATABASE, JwtConfig("s", "i", "a"), revealCodes = false),
                purchaseModule(NO_DATABASE),
                esimModule(NO_DATABASE),
                usageModule(NO_DATABASE),
                io.konekt.serverModule(KonektTrace(agent = null)),
                org.koin.dsl.module { single { kotlinx.serialization.json.Json } },
            )

        // Everything the application installs, petich's included. That one is left out of `verify()`
        // because it needs a live Database, and it is needed HERE because the injection guard reads
        // definitions rather than building them — a saga engine and a broker connection are bindings
        // whether or not anything ever resolves them.
        val everything =
            all +
                io.konekt.petichModule(
                    NO_DATABASE,
                    io.konekt.KonektConfig(
                        port = 0,
                        database = io.konekt.db.DatabaseConfig("", "", ""),
                        jwt = JwtConfig("s", "i", "a"),
                        revealOtpCodes = false,
                        brokerHost = "broker",
                        brokerPort = 9092,
                        paymentMode = io.konekt.feature.purchase.server.data.MockPaymentGateway.Mode.APPROVE,
                        paymentDelay = kotlin.time.Duration.ZERO,
                        simulateTraffic = false,
                        migrateOnly = false,
                    ),
                )
    }

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
            // The tracy agent inside KonektTrace. It is never a BINDING — the composition root builds
            // it before the container and hands over the wrapper, precisely so that a deployment
            // without tracy has a `KonektTrace` holding null rather than a missing definition. Koin's
            // `verify` reflects on the constructor and cannot see that, so it is named here.
            ru.workinprogress.tracy.agent.TracyAgent::class,
            // the application's single Json, assembled in Application.kt
            kotlinx.serialization.json.Json::class,
            // petichModule, which needs a live Database and so is not verified here
            ru.workinprogress.petich.PetichEngine::class,
            ru.workinprogress.petich.PetichRepository::class,
            // The composition root's own module composes across features: TrafficChain names the
            // broker, the counters and the card builder, and each of those is another module's. This
            // is the list growing for the reason it was meant to — a feature genuinely reaching
            // across, which is worth noticing rather than waving through.
            io.konekt.events.BrokerConnection::class,
            io.konekt.feature.usage.server.domain.UsageCounters::class,
            io.konekt.feature.usage.server.domain.ConsumeUsageUseCase::class,
            io.konekt.feature.usage.server.data.UsageCounterCards::class,
            io.konekt.realtime.ComponentBroadcaster::class,
            io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster::class,
            // NOT provided by anything, and the entry is still honest. Every feature module CAPTURES
            // its Database in the closure that builds a repository rather than resolving one from
            // the graph — `usageModule(database)` — and `verify()` cannot tell a captured value from
            // a missing binding. It only ever asks when a definition is declared with its CONCRETE
            // type, which is why this never came up while every binding named an interface.
            org.jetbrains.exposed.v1.jdbc.Database::class,
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
        // The eSIM feature binds the manager behind an interface, and the mock has three
        // defaulted constructor parameters — exactly the shape `verify()` alone would pass and a
        // resolution would not.
        assertNotNull(koin.get<SmDpPlus>(), "the profile manager is not in the graph")
        // One instance behind two interfaces, and resolving one of them is what proves the
        // aliasing rather than two separate singles.
        assertNotNull(koin.get<UsageCounters>(), "the counters are not in the graph")
    }
}
