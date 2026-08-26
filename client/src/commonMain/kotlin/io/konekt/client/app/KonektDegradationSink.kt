package io.konekt.client.app

import io.github.youndie.kompot.KompotDegradationKind
import io.github.youndie.kompot.KompotDegradationSink

// WHERE A DEGRADATION GOES, which until now was nowhere.
//
// The renderer has reported an unknown component through kompot's sink since B-05, and konekt bound
// no sink — so the toolkit's default took it, an unknown component was drawn correctly and counted by
// nothing, and the blindness youndie/kompot#81 was filed about survived being fixed upstream. A
// placeholder nobody counts is indistinguishable from a screen that never degraded.
//
// THE OUTPUT IS A PARAMETER RATHER THAN A DEPENDENCY, and that is not indirection for its own sake:
// what a client can report to differs by platform, measured rather than assumed. katcher publishes
// every Apple target since `client:0.6.2`; tracy's agent publishes `jvm`, `linux_*` and `macos_arm64`
// and NO iOS target at all (youndie/tracy#16), so structured logging is unavailable on the platform
// where an out-of-date build is likeliest — a phone updates on the subscriber's schedule.
//
// So the composition root chooses, and this module stays free of an agent dependency it could not
// satisfy everywhere.
class KonektDegradationSink(
    private val record: (KonektDegradation) -> Unit,
) : KompotDegradationSink {
    override fun onUnknown(
        kind: KompotDegradationKind,
        originalType: String,
        drawnAsFallback: Boolean,
    ) {
        record(KonektDegradation(kind, originalType, drawnAsFallback))
    }
}

// What was degraded, as a value. `originalType` is the field worth indexing wherever this lands: the
// question a record like this exists to answer is WHICH type and how often, and a wire name buried in
// a message string cannot be counted.
data class KonektDegradation(
    val kind: KompotDegradationKind,
    val originalType: String,
    // False when a placeholder was drawn and true when the server named an equivalent the client
    // could draw instead. A hole and a substitution are different facts about a screen, and folding
    // them together would make the count useless for deciding whether anybody has to act.
    val drawnAsFallback: Boolean,
)
