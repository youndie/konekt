package io.konekt.observability

import io.github.youndie.kore.observability.KoreObservability
import io.github.youndie.kore.observability.ObservabilitySettings
import io.github.youndie.kore.observability.installKoreObservability
import io.konekt.time.KonektClock
import io.ktor.server.application.Application

// THE THREE AGENTS, IN ONE CALL (konekt#30, konekt#35).
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
// METRIK WAS INSTALLED SEPARATELY HERE FOR ONE VERSION, and the reason is worth keeping even though
// the code is gone. kore's wiring could not set metrik's aggregation window, and metrik's default is
// 60 seconds: the agent sends a window when the window CLOSES, and `ObservabilityScenarioTest` waits
// 20 seconds for metrik to have counted a request. At the default that assertion passes exactly when
// a boundary happens to fall inside its wait — it passed four times and then failed on a freshly
// rebuilt stand, server up 41 seconds. `ObservabilitySettings.metrikWindow` exists as of kore 0.1.4
// (youndie/kore#68), so the split is deleted rather than left working.
fun Application.configureObservability(
    settings: ObservabilitySettings,
    // THE RESOLVED RELEASE, not the raw environment value. It is the compiled-in `version+commit`
    // unless `KONEKT_RELEASE` overrides it, and it is the same object `/version` serves — kore's rule
    // 4, one value in one place. A deploy marker and a crash group that named something the running
    // binary did not report is the disagreement nobody could previously see.
    release: String,
    clock: KonektClock,
): KoreObservability =
    installKoreObservability(
        settings =
            ObservabilitySettings(
                service = settings.service,
                release = release,
                instance = settings.instance,
                environment = settings.environment,
                tracy = settings.tracy,
                metrik = settings.metrik,
                katcher = settings.katcher,
                katcherCacheDir = settings.katcherCacheDir,
                tracySampleRate = settings.tracySampleRate,
                metrikWindow = settings.metrikWindow,
            ),
        // THE INJECTED CLOCK, not `Clock.System`. Every deadline in this build takes one (`B-33`) and
        // a guard reads the sources to keep it that way — an agent stamping every record off the wall
        // clock would be the one place a test could not move time.
        clock = { clock.now().toEpochMilliseconds() },
    )
