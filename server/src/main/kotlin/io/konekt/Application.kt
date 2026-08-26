package io.konekt

import io.github.youndie.kompot.KompotComponent
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
import io.konekt.feature.auth.server.data.RevealedCodes
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
import io.konekt.feature.purchase.server.data.topUpRoutes
import io.konekt.feature.purchase.server.domain.PURCHASE_SAGA_TYPE
import io.konekt.feature.purchase.server.domain.PurchaseConfirmation
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.TOP_UP_SAGA_TYPE
import io.konekt.feature.purchase.server.domain.TopUpPayload
import io.konekt.feature.purchase.server.domain.topUpInterceptors
import io.konekt.feature.usage.server.data.usageModule
import io.konekt.http.configureStatusPages
import io.konekt.mocks.traffic.TrafficChain
import io.konekt.observability.KonektTrace
import io.konekt.observability.ObservabilityConfig
import io.konekt.observability.configureObservability
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.realtime.realtimeRoutes
import io.konekt.screens.dev.EsimTransferWidgetComponent
import io.konekt.screens.dev.forwardCompatRoutes
import io.konekt.screens.homeRoutes
import io.konekt.time.KonektClock
import io.konekt.time.SystemClock
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.qualifier.named
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

// THE AUTH TIER OF A ROUTE, as a value rather than as indentation.
//
// Until this existed the tier was readable only from the shape of the `routing { }` block below: a
// route inside `authenticate { }` is the user tier and a route outside it is public. That is a real
// answer and an unquotable one — nothing outside this file could ask it, and "public because nobody
// decided" is exactly the tier that is discovered during an incident. As a value it travels: into
// the OpenAPI document, where every operation carries `security`, and out of that into the
// conformance walk, which asks a secured endpoint for a 401.
enum class AuthTier {
    PUBLIC,
    USER,
}

// One entry of the route table: a tier, and the routes that sit at it.
class RouteGroup(
    val tier: AuthTier,
    val mount: Route.() -> Unit,
)

// THE ROUTE TABLE, and it is read twice: `module` mounts it into the running server, and
// `io.konekt.openapi` mounts it into an application with no database in order to walk the routing
// tree it produces.
//
// One list rather than two, because a generator carrying a route list of its own is a second copy of
// this contract — the thing this repository refuses everywhere else — and the document would then
// describe the generator's idea of the server rather than the server. What the document says about
// paths, methods and tiers is therefore not asserted anywhere: it is read out of the tree Ktor
// actually built.
val konektRoutes: List<RouteGroup> =
    listOf(
        // The way in. Neither of these can sit behind a session; what protects them is the lockout
        // in the use cases, and refresh is public because the refresh token IS the credential.
        RouteGroup(AuthTier.PUBLIC) {
            authRoutes()
            sessionRoutes()
        },
        // The user tier. Decided here, in the composition root, while the shape of a token is the
        // auth feature's business.
        RouteGroup(AuthTier.USER) {
            authenticatedSessionRoutes()
            purchaseRoutes()
            topUpRoutes()
            esimWizardRoutes()
            homeRoutes()
            realtimeRoutes()
        },
    )

// The development route that reads back a one-time code, in an entry of its own because it is THE
// ONE route whose existence is a configuration decision rather than a fact about the build.
//
// `RevealedCodes` arrives as a function rather than as a value so that mounting the table needs no
// container: the composition root passes `{ getKoin().get() }` and the document generator passes a
// plain instance. A parameter here would have made the whole table need a Koin, and a Koin needs a
// database.
// The development screens, in an entry of their own for the same reason: their existence is a
// configuration decision rather than a fact about the build. Public, because the screen carries no
// subscriber's data — two invented counters and a component nobody can render.
val devScreensRouteGroup: RouteGroup = RouteGroup(AuthTier.PUBLIC) { forwardCompatRoutes() }

fun devOtpRouteGroup(revealed: () -> RevealedCodes): RouteGroup =
    RouteGroup(AuthTier.PUBLIC) { devOtpRoutes(revealed()) }

// The one place where a tier becomes an `authenticate { }`. It is a function rather than four lines
// inside `routing { }` because the document generator needs the same translation: a tier that meant
// one thing when the server mounted it and another when the document was written would be a
// document that is wrong about the only thing it is uniquely able to say.
fun Route.mountKonektRoutes(groups: List<RouteGroup>) {
    groups.forEach { group ->
        when (group.tier) {
            AuthTier.PUBLIC -> group.mount(this)
            AuthTier.USER -> authenticate(AUTH_JWT) { group.mount(this) }
        }
    }
}

// The composition root. A feature contributes bindings and routes; plugins are installed once, here.
fun Application.module(config: KonektConfig) {
    // BEFORE THE ROUTES, because metrik's plugin measures a call and tracy's opens a span around it:
    // installed later they would observe whatever was registered after them, which is nothing.
    // Whether any of the three runs at all is decided by the environment, not here.
    //
    // The agent is built BEFORE the container rather than loaded into it afterwards, so that
    // `KonektTrace` is bound by the application's own `modules()`. A binding added later is one
    // `RoutesResolveWhatTheyInjectTest` cannot see, and that guard exists because Koin resolves
    // lazily: the process starts, the health check passes, and the route answers 500 to its first
    // caller.
    val tracy = configureObservability(ObservabilityConfig.fromEnv(), SystemClock)

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
            serverModule(KonektTrace(tracy)),
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

        // OFF unless asked for. It publishes fictional usage against real counters, so a deployment
        // that forgot the switch must not be one that quietly spends its subscribers' allowances.
        //
        // Started HERE and nowhere else, which is the point: both halves of this chain existed and
        // were covered end to end for a week while nothing constructed either of them.
        if (config.simulateTraffic) {
            workers.launch { koin.get<TrafficChain>().start(workers) }
        }
    }
    monitor.subscribe(ApplicationStopping) {
        workers.cancel()
        // Closed explicitly rather than left to the process ending: the producer holds a coroutine
        // and a socket, and a test that builds an application leaves both behind otherwise.
        getKoin().get<BrokerConnection>().close()
    }

    // THE WHOLE OF THE ROUTING, and deliberately nothing beside it. What is mounted and at which
    // tier is `konektRoutes`; this block only hands it a Route. A route registered here directly
    // would be a route the OpenAPI document never sees — and `CompositionRootRoutesTest` reads this
    // file and refuses that shape, because a call is control flow and cannot be verified any other
    // way.
    routing {
        mountKonektRoutes(
            konektRoutes +
                (if (config.revealOtpCodes) listOf(devOtpRouteGroup { getKoin().get() }) else emptyList()) +
                (if (config.devScreens) listOf(devScreensRouteGroup) else emptyList()),
        )
    }
}

// What the composition root itself provides: the things that belong to no feature.
//
// A NAMED FUNCTION rather than an inline `module { }`, so a test can install exactly what the
// application installs. An inline one is invisible from outside, and every binding in it is a binding
// only a running process has ever resolved.
fun serverModule(trace: KonektTrace) =
    module {
        // THE SERVER COULD NOT START WITHOUT THIS LINE, and nothing said so until a stand tried.
        // `Application.kt` resolved a broadcaster on startup and `realtimeRoutes` injected one, and
        // no module ever bound it — every route test and the smoke test build their own graph, so
        // each supplied its own and none asked whether the application does.
        //
        // The in-memory bus is the default and is right for one instance; `kompot-realtime-redis` is
        // the multi-instance backend and this product has one.
        // Bound ALWAYS, holding whatever there is: a feature that logs must compile and run in a
        // deployment with no tracy, and Koin cannot bind a null.
        single { trace }
        single { KompotUpdateBroadcaster() }
        single { ComponentBroadcaster(get(), get()) }
        single { TrafficChain(get(), get(), get(), get(), get(), get()) }
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
fun petichModule(
    database: Database,
    config: KonektConfig,
) = module {
    single<PetichTable> { PetichTable(get()) }
    single<OutboxEventsTable> { OutboxEventsTable() }
    single<OutboxAwarePetichRepository> { ExposedPetichRepository(database, get(), get()) }
    single<PetichRepository> { get<OutboxAwarePetichRepository>() }

    // TWO ENGINES OVER ONE SAGA TABLE, named by saga type. petich resolves nothing by type itself —
    // an engine is a fixed interceptor list — so handing a top-up to the purchase engine finds no
    // step that supports its payload, completes a saga that did nothing, and reports success.
    single(named(PURCHASE_SAGA_TYPE)) {
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

    single(named(TOP_UP_SAGA_TYPE)) {
        PetichEngine(
            interceptors = topUpInterceptors(balances = get(), payments = get(), json = get()),
            repository = get<OutboxAwarePetichRepository>(),
            // The same requireOutbox for the same reason: petich degrades quietly to a plain update
            // when handed a repository that cannot store events, and a top-up whose completion nobody
            // was told about looks exactly like one that worked.
            config = PetichEngineConfig(requireOutbox = true),
            clock = get<KonektClock>().asPetichClock(),
        )
    }

    single {
        SuspendedPetichSweeper(
            repository = get<OutboxAwarePetichRepository>() as ExpiringPetichRepository,
            // BY SAGA TYPE, and `{ get() }` stopped being correct the moment there were two engines.
            // The sweeper rolls back sagas that waited too long, and rolling one back with another
            // type's interceptor list runs the wrong compensations — or none, which is the quiet one:
            // the money stays held and the saga is marked failed.
            //
            // Only the purchase saga ever suspends today, so this dispatch is exercised by one branch.
            // It is written for both anyway: the day a top-up grows a confirmation step, the failure
            // is a balance that is never returned rather than a compile error.
            engineFor = { saga -> get(named(saga.type)) },
            clock = get<KonektClock>().asPetichClock(),
        )
    }
}

// What petich needs to write a saga down. Separate from the wire modules above it because it is not
// a wire at all — these types never leave the process, they go into a column.
private val petichSerializersModule =
    SerializersModule {
        polymorphic(PetichPayload::class) {
            subclass(PurchasePayload::class)
            // Without this line no top-up can be created at all: petich writes its payload
            // polymorphically into the saga row, and an unregistered subclass is a 500 on the first
            // POST. Not hypothetical — it is one of the four defects the stand found on its first
            // boot, when the PURCHASE payload was the one missing from here.
            subclass(TopUpPayload::class)
        }
        polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
        polymorphic(ResumePayload::class) { subclass(PurchaseConfirmation::class) }
    }

// THE ONE COMPONENT TYPE THE CLIENT MUST NOT HAVE, and it is declared in `:server` rather than in
// the dictionary module for exactly that reason: a type in the dictionary is a type the client
// registers, and a component that cannot arrive unknown demonstrates nothing. Registered here so the
// server can put it on the wire; absent from the client's registry, so it arrives as an
// `UnknownComponent` and the replacement renderer draws it. See B-25.
private val devScreensSerializersModule =
    SerializersModule {
        polymorphic(KompotComponent::class) { subclass(EsimTransferWidgetComponent::class) }
    }

// The application's Json: the toolkit's actions and components, konekt's own dictionary, and the
// saga's payloads. One instance, bound in the graph, because two Json configurations that differ by
// one module produce a wire nobody can debug.
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
            // THE SAGA'S OWN TYPES, and without them no purchase can be created at all: petich
            // writes its payload polymorphically into the saga row, and an unregistered subclass is
            // a 500 on the first POST. Every saga test builds this by hand, which is exactly why
            // nothing noticed the application did not — a stand was the first thing to try.
            //
            // Note what this makes true: the STORAGE format of a saga now depends on this Json's
            // `classDiscriminator`. Changing it would make already-persisted sagas unreadable, which
            // is the reason @SerialName is on those payloads rather than the class name being used.
            petichSerializersModule +
            devScreensSerializersModule +
            // Hand-written, because actions are not generated: @KompotComponentMarker covers
            // components and the KompotAction hierarchy is registered by hand. Omitting it
            // fails nothing at build time and fails every wizard step at runtime.
            esimActionsSerializersModule
    }
