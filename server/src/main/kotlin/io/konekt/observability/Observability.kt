package io.konekt.observability

import io.github.youndie.kore.observability.KoreObservability
import io.github.youndie.kore.observability.ObservabilitySettings
import io.github.youndie.kore.observability.installKoreObservability
import io.github.youndie.metrik.agent.Metrik
import io.konekt.time.KonektClock
import io.ktor.server.application.Application
import io.ktor.server.application.install

// THE THREE AGENTS: TWO FROM KORE, ONE STILL OURS, AND THE SPLIT IS MEASURED RATHER THAN CHOSEN
// (konekt#30, konekt#35).
//
// What this file used to be is worth saying, because the defect it carried is the reason kore exists.
// It installed tracy's plugin and its delivery — correctly, both, which the toolkit's own README
// example gets wrong — and then **dropped the delivery reference on the floor**:
//
//     TracyDelivery(agent, agentConfig).start(this)
//
// `TracyDelivery.stop(grace)` is written for exactly the moment a process goes down: it cancels the
// loop and makes one last bounded flush. Nothing could call it, because there was no object to call
// it on. Every shutdown lost up to one flush interval of records — **including the records explaining
// the shutdown**, which are the least replaceable ones in the buffer, and it was invisible in the way
// that matters: nothing failed, nothing was logged, and what vanished was what nobody was waiting on.
// That is `konekt#30`, and holding kore's handle is what closes it: `KoreObservability` is a
// `ShutdownParticipant`, so the flush is the `telemetry` stage of the sequence in `Application.kt`
// rather than a call somebody has to remember.
//
// kore runs that stage LAST, after the pools — read off a real transcript rather than assumed:
// `RELEASE_POOLS COMPLETED in 9.488358ms` then `RELEASE_TELEMETRY COMPLETED in 2.332318ms`. That is
// the right way round. The agents speak HTTP and need nothing this process is closing, and putting
// them last is what lets the last thing they report be the shutdown itself.
//
// ## Why metrik is installed here and not by that call
//
// kore installs metrik's plugin itself and exposes **no way to set the aggregation window**. The
// agent sends a window when the window CLOSES, and metrik's default is 60 seconds — read out of
// `MetrikConfig.<init>` in the published `agent-jvm:0.2.18`, `ldc2_w 60000l`. The stand's whole e2e
// run is shorter than that, and `ObservabilityScenarioTest` waits 20 seconds for metrik to have
// counted a request.
//
// MEASURED, and the first measurement was wrong, which is the part worth keeping. At 60000 the
// scenario passed four times in a row — and then failed on a freshly rebuilt stand, server up 41
// seconds: `waited 20s for: metrik to have seen konekt-server`. The four passes were a stand that had
// been up for minutes, so a window boundary fell inside the wait. The setting is load-bearing and the
// first result measured something else.
//
// So metrik is handed to kore as `null` and installed below with konekt's window. Nothing of
// `konekt#30` is lost by that: metrik is the one of the three with no flush to hold a handle for —
// it subscribes its own stop to `ApplicationStopping` and the open window is gone either way.
//
// Reported as youndie/kore#68. When `ObservabilitySettings` can carry the window, this function
// becomes the one call again and `METRIK_WINDOW_MS` goes back to being kore's key.
fun Application.configureObservability(
    settings: ObservabilitySettings,
    metrikWindowMs: Long,
    clock: KonektClock,
): KoreObservability {
    val observability =
        installKoreObservability(
            // metrik withheld deliberately — see above. Everything else is kore's.
            settings =
                ObservabilitySettings(
                    service = settings.service,
                    release = settings.release,
                    instance = settings.instance,
                    environment = settings.environment,
                    tracy = settings.tracy,
                    metrik = null,
                    katcher = settings.katcher,
                    katcherCacheDir = settings.katcherCacheDir,
                    tracySampleRate = settings.tracySampleRate,
                ),
            // THE INJECTED CLOCK, not `Clock.System`. Every deadline in this build takes one (`B-33`)
            // and a guard reads the sources to keep it that way — an agent stamping every record off
            // the wall clock would be the one place a test could not move time.
            clock = { clock.now().toEpochMilliseconds() },
        )

    settings.metrik?.let { metrik ->
        install(Metrik) {
            service = settings.service
            apiKey = metrik.key
            endpoint = metrik.endpoint
            instanceId = settings.instance
            release = settings.release
            windowMs = metrikWindowMs
        }
    }

    return observability
}
