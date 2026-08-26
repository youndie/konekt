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
        // WHAT REACHES THIS METHOD IS ALWAYS A DECODE FAILURE, because that is the only thing kompot
        // can know about: it owns the wire and not konekt's dictionary. The other cause is set at the
        // one call site that knows it — see `KonektDegradation.Cause`.
        record(KonektDegradation(kind, originalType, drawnAsFallback, KonektDegradation.Cause.UNDECODABLE))
    }

    // THE OTHER CAUSE, and it needs a method of its own because kompot's interface cannot carry it:
    // `onUnknown` is about the WIRE, which the toolkit owns, and "in konekt's dictionary with no
    // renderer" is a fact about konekt's registry that the toolkit has no way to learn.
    //
    // A deployment that binds some other `KompotDegradationSink` still hears about the component —
    // `UndrawableComponentRenderer` falls back to `onUnknown` — it just cannot be told which of the
    // two happened. That is a smaller loss than a second sink interface nobody would bind.
    fun onUndrawable(originalType: String) {
        record(
            KonektDegradation(
                kind = KompotDegradationKind.UNKNOWN_COMPONENT,
                originalType = originalType,
                drawnAsFallback = false,
                cause = KonektDegradation.Cause.UNDRAWABLE,
            ),
        )
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
    // WHOSE FAULT IT IS, and it is the field this record was missing for most of the build's life.
    val cause: Cause = Cause.UNDECODABLE,
) {
    // THE TWO WAYS A CLIENT FAILS TO RENDER, and the whole forward-compatibility argument only ever
    // covered one of them.
    //
    // They are deliberately INDISTINGUISHABLE ON SCREEN: a subscriber meets the same block and the
    // same sentence, because "update to see it" is the only move either one leaves them. They must be
    // distinguishable in the RECORD, because the two mean opposite things to whoever reads it — one
    // says the client is behind the server, the other says this build shipped a dictionary entry it
    // never wired up.
    enum class Cause {
        // A type this build has never heard of. It decoded into `UnknownComponent`; the server is
        // ahead, and the answer is a release.
        UNDECODABLE,

        // A type in konekt's own dictionary with no renderer. It decoded into its own class and the
        // registry had nothing to draw it with — a gap inside one build rather than between two.
        // Invisible until B-44: not an `UnknownComponent`, so it reached no block and no sink, and
        // what appeared was the toolkit's red fallback on a screen nobody was counting.
        UNDRAWABLE,
    }
}
