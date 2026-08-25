package io.konekt

import io.konekt.http.configureStatusPages
import io.konekt.time.timeModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

// The composition root. Everything a feature needs is installed here once, and a feature module
// contributes bindings and routes rather than plugins.
fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(timeModule)
    }

    install(ContentNegotiation) { json() }

    // Before routing, and the reason is not order of execution but order of thought: a route written
    // after this exists answers `.getOrThrow()` and stops, because the mapping is already there to
    // catch what it throws.
    configureStatusPages()

    routing {
        // The one route B-01 owns. It exists so the compose stand's healthcheck can ask the process
        // a question rather than ask the kernel whether a port accepts — the kernel accepts into the
        // backlog with no help from a hung process.
        get("/health") {
            call.respondText("ok")
        }
    }
}
