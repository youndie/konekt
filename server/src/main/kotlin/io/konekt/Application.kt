package io.konekt

import io.github.youndie.kompot.auth.kompotAuthSerializersModule
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.db.DatabaseFactory
import io.konekt.events.BooblikOutboxPublisher
import io.konekt.events.BrokerConnection
import io.konekt.feature.auth.server.data.AUTH_JWT
import io.konekt.feature.auth.server.data.authModule
import io.konekt.feature.auth.server.data.authRoutes
import io.konekt.feature.auth.server.data.authenticatedSessionRoutes
import io.konekt.feature.auth.server.data.configureAuthentication
import io.konekt.feature.auth.server.data.devOtpRoutes
import io.konekt.feature.auth.server.data.sessionRoutes
import io.konekt.feature.esim.server.data.esimModule
import io.konekt.feature.esim.server.data.esimWizardRoutes
import io.konekt.feature.esim.shared.api.esimActionsSerializersModule
import io.konekt.feature.purchase.server.data.MockPaymentGateway
import io.konekt.feature.purchase.server.data.purchaseInterceptors
import io.konekt.feature.purchase.server.data.purchaseModule
import io.konekt.feature.purchase.server.data.purchaseRoutes
import io.konekt.feature.purchase.server.domain.PurchaseConfirmation
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.usage.server.data.usageModule
import io.konekt.http.configureStatusPages
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.realtime.realtimeRoutes
import io.konekt.screens.homeRoutes
import io.konekt.time.KonektClock
import io.konekt.time.asPetichClock
import io.konekt.time.timeModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.ExpiringPetichRepository
import ru.workinprogress.petich.OutboxAwarePetichRepository
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichEngineConfig
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.ResumePayload
import ru.workinprogress.petich.SimpleEnrichedPayload
import ru.workinprogress.petich.SuspendedPetichSweeper
import ru.workinprogress.petich.outbox.OutboxPublisher
import ru.workinprogress.petich.outbox.OutboxRelayWorker
import ru.workinprogress.petich.postgres.ExposedOutboxRepository
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable

// The engine is CIO because the load-bearing endpoint of this server is SSE — many long-lived,
// mostly idle streams, which is the profile a coroutine-per-connection engine is shaped for and a
// thread pool is not. See docs/research/research-stack.md D19.
//
// A WARNING that costs nothing here and will cost an afternoon later: io.ktor.client.engine.cio.CIO
// carries the same simple name. The moment this file also builds an HttpClient — which it will, for
// the mocks — the two imports collide, and the one that wins hands `embeddedServer` the CLIENT
// engine. Import it as `io.ktor.server.cio.CIO as ServerCIO` at that point, not before: an alias for
// a name nothing collides with is noise.
fun main() {
    val config = KonektConfig.fromEnv()

    // Migrate-only mode: the deploy runs this image once, before the application pods roll, so the
    // schema is current when the first new process starts and two processes never race to migrate.
    // See B-36 for why the migration itself is written the way it is.
    if (config.migrateOnly) {
        val applied = DatabaseFactory.migrate(DatabaseFactory.dataSource(config.database))
        println("applied $applied migrations")
        return
    }

    embeddedServer(CIO, port = config.port, host = "0.0.0.0") { module(config) }.start(wait = true)
}

// Everything that needs no database. Split out so a test — and the health check — can have a server
// without one, and so the list of plugins is readable on its own.
fun Application.baseModule(extraModules: List<org.koin.core.module.Module> = emptyList()) {
    install(Koin) {
        slf4jLogger()
        modules(listOf(timeModule) + extraModules)
    }

    install(ContentNegotiation) { json() }
    install(Resources)
    install(SSE)

    // Before routing, and the reason is not order of execution but order of thought: a route written
    // after this exists answers `.getOrThrow()` and stops, because the mapping is already there to
    // catch what it throws.
    configureStatusPages()

    routing {
        // It exists so the compose stand's healthcheck can ask the process a question rather than
        // ask the kernel whether a port accepts — the kernel accepts into the backlog with no help
        // from a hung process.
        get("/health") { call.respondText("ok") }
    }
}

// The composition root. A feature contributes bindings and routes; plugins are installed once, here.
fun Application.module(config: KonektConfig) {
    val dataSource = DatabaseFactory.dataSource(config.database)
    val database = DatabaseFactory.connect(dataSource)

    // Not migrating here. Migrations run as their own step before any process serves (see main), so
    // that during a rolling deploy the schema is already current and two processes never race.

    baseModule(
        listOf(
            module { single { kompotJson } },
            authModule(database, config.jwt, revealCodes = config.revealOtpCodes),
            purchaseModule(database, config.paymentMode, config.paymentDelay),
            esimModule(database),
            // Bound here for the first time in B-07. The counters existed, were tested, and were
            // reachable from nothing: five imports of this feature sat in this file with no use
            // beneath them.
            usageModule(database),
            petichModule(database, config),
        ),
    )

    configureAuthentication(config.jwt)

    // The workers. Started when the application starts and cancelled when it stops, so a test that
    // builds an application does not leave a poller running against a database it is about to drop.
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    monitor.subscribe(ApplicationStarted) {
        val koin = getKoin()
        koin.get<SuspendedPetichSweeper>().start(workers)
        koin.get<OutboxRelayWorker>().start(workers)
        koin.get<KompotUpdateBroadcaster>().start(workers)
    }
    monitor.subscribe(ApplicationStopping) {
        workers.cancel()
        // Closed explicitly rather than left to the process ending: the producer holds a coroutine
        // and a socket, and a test that builds an application leaves both behind otherwise.
        getKoin().get<BrokerConnection>().close()
    }

    routing {
        authRoutes()
        sessionRoutes()

        // The user tier. What is inside `authenticate` is decided here, in the composition root,
        // while the shape of a token is the feature's business.
        authenticate(AUTH_JWT) {
            authenticatedSessionRoutes()
            purchaseRoutes()
            esimWizardRoutes()
            homeRoutes()
            realtimeRoutes()
        }

        if (config.revealOtpCodes) {
            devOtpRoutes(getKoin().get())
        }
    }
}

// The saga engine and its storage.
//
// ONE ENGINE PER SAGA TYPE, sharing one table. The sweeper resolves the owning engine per saga rather
// than taking one, because rolling a purchase back with another type's interceptor list would run the
// wrong compensations — or none.
//
// requireOutbox is on. petich degrades quietly to a plain update when handed a repository that cannot
// store events, and the saga still completes with correct state while nobody downstream is ever told.
// Nothing about that is visible from a test that asserts the saga finished.
private fun Application.petichModule(
    database: Database,
    config: KonektConfig,
) = module {
    single<PetichTable> { PetichTable(get()) }
    single<OutboxEventsTable> { OutboxEventsTable() }
    single<OutboxAwarePetichRepository> { ExposedPetichRepository(database, get(), get()) }
    single<PetichRepository> { get<OutboxAwarePetichRepository>() }

    single {
        PetichEngine(
            interceptors =
                purchaseInterceptors(
                    balances = get(),
                    entitlements = get(),
                    plans = get(),
                    payments = get(),
                    grants = get(),
                    json = get(),
                ),
            repository = get<OutboxAwarePetichRepository>(),
            config =
                PetichEngineConfig(
                    requireOutbox = true,
                    // The canvas tells the subscriber a settlement "usually takes under 15
                    // seconds", and petich's default EXECUTION bound is 10 — so the screen
                    // describes a provider the engine would cancel. Raised rather than the copy
                    // lowered: a timeout that fires before the provider has answered turns a slow
                    // approval into a rollback nobody asked for.
                    // The defaults, with one entry replaced. `PetichPhase.timeoutMs` is not
                    // visible from outside petich, so the defaults are taken from a default
                    // config rather than rebuilt — which is also the form that keeps every other
                    // phase on whatever petich decides next.
                    phaseTimeoutsMs =
                        PetichEngineConfig().phaseTimeoutsMs +
                            (
                                PetichPhase.EXECUTION to
                                    MockPaymentGateway.EXECUTION_PHASE_TIMEOUT.inWholeMilliseconds
                            ),
                ),
            clock = get<KonektClock>().asPetichClock(),
        )
    }

    // The broker connection and the relay that feeds it. petich provides at-least-once delivery
    // with backoff and dead-lettering and says outright that the transport is the application's
    // — this is where that sentence is answered.
    single { ExposedOutboxRepository(database, get()) }
    single { BrokerConnection(config.brokerHost, config.brokerPort) }
    single<OutboxPublisher> { BooblikOutboxPublisher(get<BrokerConnection>().producer, get()) }

    single {
        OutboxRelayWorker(
            repository = get<ExposedOutboxRepository>(),
            publisher = get(),
        )
    }

    single {
        SuspendedPetichSweeper(
            repository = get<OutboxAwarePetichRepository>() as ExpiringPetichRepository,
            engineFor = { get() },
            clock = get<KonektClock>().asPetichClock(),
        )
    }
}

// The application's Json: the toolkit's actions and components plus konekt's own dictionary. One
// instance, bound in the graph, because two Json configurations that differ by one module produce a
// wire nobody can debug.
private val kompotJson: Json =
    Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        serializersModule =
            kompotCoreSerializersModule +
            kompotStandardSerializersModule +
            generatedStandardSerializersModule +
            generatedKonektSerializersModule +
            kompotAuthSerializersModule +
            // Hand-written, because actions are not generated: @KompotComponentMarker covers
            // components and the KompotAction hierarchy is registered by hand. Omitting it
            // fails nothing at build time and fails every wizard step at runtime.
            esimActionsSerializersModule
    }
