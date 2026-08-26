package io.konekt.client.observability

import io.konekt.client.app.KonektDegradation
import io.konekt.time.KonektClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.workinprogress.katcher.Katcher
import ru.workinprogress.tracy.agent.AgentConfig
import ru.workinprogress.tracy.agent.TracyAgent
import ru.workinprogress.tracy.agent.TracyDelivery

// WHERE A CLIENT-SIDE DEGRADATION GOES, which until now was nowhere on any platform and could not go
// anywhere on one of them.
//
// `KonektDegradationSink` has taken a `record` lambda since B-26's first half, precisely so the output
// could be chosen by whoever assembles the application — because what a client can report to differed
// by platform. katcher published every Apple target; tracy's agent published `jvm`, `linux_*` and
// `macos_arm64` and no iOS target at all (youndie/tracy#16), so the structured half was unavailable
// exactly where an out-of-date build is likeliest: a phone updates on the subscriber's schedule.
//
// tracy `0.1.13` publishes the three iOS targets. So this file is `commonMain` and the same record
// goes to the same two places from a desktop window and from a phone.
class KonektClientObservability(
    private val agent: TracyAgent?,
    private val delivery: TracyDelivery?,
    private val scope: CoroutineScope,
) {
    // Started separately from construction, and never implicitly: the DELIVERY is what empties the
    // buffer the agent fills, so an application that built an agent and started no delivery logs into
    // memory and reports nothing. That is the mistake the toolkit's own README example exists to
    // prevent, and it is worth one explicit call.
    fun start() {
        delivery?.start(scope)
    }

    // THE RECORDER, in the shape `KonektDegradationSink` asks for.
    //
    // It is NOT suspend, because a Compose renderer is not: the sink is called from a
    // `LaunchedEffect` in `UnknownBlockRenderer` and tracy's logging is `suspend` by design — the
    // trace context lives in the coroutine. So the record is launched into this object's scope, and a
    // screen leaving the tree cancels a record still in flight rather than outliving it.
    fun recorder(): (KonektDegradation) -> Unit =
        { degradation ->
            // THE BREADCRUMB IS FIRST AND IS NOT LAUNCHED. It is an in-memory append that katcher
            // attaches to the NEXT crash, so it must have happened before that crash — and a
            // breadcrumb that lost a race with a stack trace is a breadcrumb that explains nothing.
            Katcher.addBreadcrumb(
                message = "unknown component: ${degradation.originalType}",
                type = "degradation",
                data =
                    mapOf(
                        "originalType" to degradation.originalType,
                        "kind" to degradation.kind.name,
                        "drawnAsFallback" to degradation.drawnAsFallback.toString(),
                    ),
            )

            // No tracy configured: the breadcrumb above is the whole record, which is a coherent
            // thing to be. `agent` is nullable rather than a no-op logger for the reason `KonektTrace`
            // is on the server — a no-op answers every call successfully and writes nothing, which is
            // indistinguishable from a working agent at the moment somebody goes looking.
            val logger = agent?.logger(LOGGER)
            if (logger != null) {
                scope.launch {
                    // `warn` rather than `info`: a screen the client could not draw is not routine, and
                    // the level is what decides whether anybody sees it without going looking.
                    logger.warn("client could not render a component") {
                        // INDEXED, and that is the whole reason this record is worth writing. tracy
                        // turns an indexed field into an entity key, so "which wire type is this build
                        // failing on, and how often" is answerable. The same line without the flag
                        // produces a record tracy stores and nobody can count.
                        field("originalType", degradation.originalType, indexed = true)
                        field("kind", degradation.kind.name)
                        // A hole and a substitution are different facts about a screen, and folding
                        // them together would make the count useless for deciding whether anybody
                        // must act.
                        field("drawnAsFallback", degradation.drawnAsFallback)
                    }
                }
            }
        }

    companion object {
        const val LOGGER: String = "KonektClientRender"

        // THE SERVICE NAME THE CLIENT REPORTS UNDER, and it is deliberately not `konekt-server`. Two
        // halves reporting under one name make "where did this happen" unanswerable, which is the
        // question the record exists to answer.
        const val SERVICE: String = "konekt-client"

        // Half-configured is REFUSED rather than quietly switched off, the same rule the server's
        // `ObservabilityConfig` follows. An endpoint without a key is a build that meant to be
        // observed and is silent, and silence is what an agent that is switched off looks like too.
        // Both absent is a decision; one absent is a mistake.
        fun of(
            endpoint: String?,
            apiKey: String?,
            release: String,
            instanceId: String,
            scope: CoroutineScope,
            // INJECTED, and not defaulted to `Clock.System`. Every deadline and every timestamp in
            // this build takes a `KonektClock` (B-33) and a guard reads the sources to keep it that
            // way — an agent stamping every record off the wall clock would be the one place a test
            // could not move time. It caught this file the hour it was written.
            clock: KonektClock,
        ): KonektClientObservability {
            require((endpoint.isNullOrBlank()) == (apiKey.isNullOrBlank())) {
                "tracy is half-configured for the client: an endpoint without a key, or a key without " +
                    "an endpoint, is a build that believes it is observed and is not"
            }

            if (endpoint.isNullOrBlank() || apiKey.isNullOrBlank()) {
                // No tracy, and the breadcrumb still works: katcher needs no endpoint to remember one.
                return KonektClientObservability(agent = null, delivery = null, scope = scope)
            }

            val config =
                AgentConfig(
                    service = SERVICE,
                    apiKey = apiKey,
                    endpoint = endpoint,
                    instanceId = instanceId,
                    release = release,
                    // 1.0 and not the default 0.01, for the reason the server's is: this is a
                    // demonstration of a handful of events, and one lost in a hundred is a
                    // demonstration that fails while somebody is watching.
                    sampleRate = 1.0,
                )
            val agent = TracyAgent(config, clock = { clock.now().toEpochMilliseconds() })
            return KonektClientObservability(agent, TracyDelivery(agent, config), scope)
        }
    }
}
