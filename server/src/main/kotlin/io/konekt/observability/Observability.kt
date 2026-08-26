package io.konekt.observability

import io.konekt.time.KonektClock
import io.ktor.server.application.Application
import io.ktor.server.application.install
import ru.workinprogress.katcher.Katcher
import ru.workinprogress.metrik.agent.Metrik
import ru.workinprogress.tracy.agent.AgentConfig
import ru.workinprogress.tracy.agent.Tracy
import ru.workinprogress.tracy.agent.TracyAgent
import ru.workinprogress.tracy.agent.TracyDelivery

// THE THREE AGENTS, INSTALLED IN ONE PLACE, and the reason they are together is that they answer
// three halves of one question. metrik says a route got slow; tracy says which order it was and what
// happened to it; katcher says it threw. Any two of them leave the reader guessing at the third.
//
// Nothing here decides WHETHER to observe — `ObservabilityConfig` does, from the environment, and it
// refuses a half-configured agent rather than starting a deployment that believes it is observed.
//
// Returns the tracy agent when there is one, because it is the only one of the three that anything
// else needs a handle on: metrik is a plugin and katcher is an object.
fun Application.configureObservability(
    config: ObservabilityConfig,
    clock: KonektClock,
): TracyAgent? {
    config.metrik?.let { metrik ->
        install(Metrik) {
            service = config.service
            apiKey = metrik.key
            endpoint = metrik.endpoint
            release = config.release
            config.metrikWindowMs?.let { windowMs = it }
        }
    }

    config.katcher?.let { katcher ->
        // The same object the iOS client starts, from the same library. What differs is only the
        // platform hook underneath it, and that is the library's business rather than ours.
        Katcher.start {
            appKey = katcher.key
            remoteHost = katcher.endpoint
            release = config.release
            environment = config.environment
        }
    }

    return config.tracy?.let { tracy ->
        val agentConfig =
            AgentConfig(
                service = config.service,
                apiKey = tracy.key,
                endpoint = tracy.endpoint,
                // The pod name in a cluster, and the container id here. Without it every instance
                // of a rolling deploy is the same instance, and "which one is slow" stops being a
                // question the data can answer.
                instanceId = System.getenv("HOSTNAME") ?: "local",
                release = config.release,
                // 1.0 and not the default 0.01, and this is a stand rather than production. A
                // demonstration whose trace is missing one time in a hundred is a demonstration
                // that fails in front of somebody; the volume here is a handful of requests.
                sampleRate = 1.0,
            )

        // THE INJECTED CLOCK, not `Clock.System`. Every deadline in this build takes one (B-33) and a
        // guard reads the sources to keep it that way — an agent stamping every record off the wall
        // clock would be the one place a test could not move time.
        val agent = TracyAgent(agentConfig, clock = { clock.now().toEpochMilliseconds() })

        // DELIVERY IS SEPARATE FROM THE PLUGIN, and installing one without the other is the mistake
        // the toolkit's own README example was written to prevent: the plugin fills a buffer and the
        // delivery is what empties it, so a server with only the plugin logs into memory and reports
        // nothing.
        TracyDelivery(agent, agentConfig).start(this)
        install(Tracy) { this.agent = agent }

        agent
    }
}
