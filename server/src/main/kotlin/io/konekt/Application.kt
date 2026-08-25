package io.konekt

import io.github.youndie.kompot.auth.kompotAuthSerializersModule
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.db.DatabaseFactory
import io.konekt.feature.auth.server.data.AUTH_JWT
import io.konekt.feature.auth.server.data.authModule
import io.konekt.feature.auth.server.data.authRoutes
import io.konekt.feature.auth.server.data.authenticatedSessionRoutes
import io.konekt.feature.auth.server.data.configureAuthentication
import io.konekt.feature.auth.server.data.devOtpRoutes
import io.konekt.feature.auth.server.data.sessionRoutes
import io.konekt.http.configureStatusPages
import io.konekt.time.timeModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

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
        ),
    )

    configureAuthentication(config.jwt)

    routing {
        authRoutes()
        sessionRoutes()

        // The user tier. What is inside `authenticate` is decided here, in the composition root,
        // while the shape of a token is the feature's business.
        authenticate(AUTH_JWT) {
            authenticatedSessionRoutes()
        }

        if (config.revealOtpCodes) {
            devOtpRoutes(getKoin().get())
        }
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
            kompotAuthSerializersModule
    }
