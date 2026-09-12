package io.konekt.observability

// WHERE THE THREE AGENTS REPORT, AND THE RULE THAT EACH IS ALL-OR-NOTHING.
//
// Every one of them answers a missing endpoint or a missing key by doing nothing: metrik's plugin has
// an `enabled` flag, tracy's delivery simply never connects, and katcher's `start` prints a line and
// returns. That is three different ways to arrive at the same failure — a deployment that MEANT to be
// observed and is silent, discovered when somebody goes looking for a trace that was never sent.
//
// So a half-configured agent is a refusal at startup rather than a quiet no-op, and an absent one is
// an explicit decision: both variables unset means off, one of the two set means the deployment is
// wrong about itself.
//
// THE REFUSAL IS NO LONGER WRITTEN HERE. It is three `ConfigPair`s in `KonektSchema`, which says the
// same thing and says it in the same pass as every other problem — the hand-written version threw on
// the first bad agent, so a deployment that had got two of them wrong found out about the second one
// on the next restart. This type is now just the shape the wiring reads.
data class ObservabilityConfig(
    // The service name IS the identifier in all three — there is no registration step anywhere — so a
    // typo does not fail, it creates a phantom service that looks healthy and receives nothing.
    val service: String,
    // A release that changes draws a deploy marker in metrik and names the build in a katcher crash
    // group. `Unspecified` is katcher's own default and it is what makes a crash unactionable.
    val release: String,
    val environment: String,
    val metrik: AgentEndpoint?,
    // THE AGENT'S AGGREGATION WINDOW. Not nullable any more: the schema declares metrik's own default
    // of 60000 explicitly, so this is always a number somebody can read in `--print-config` rather
    // than an absence that means "whatever the library does".
    //
    // Sixty seconds is right for a deployment and wrong for a stand, where the whole run is shorter
    // than one window. Left unstated it made an e2e check pass locally against a stand that had been
    // up a while and fail in CI against a fresh one — a result measured where the mechanism could not
    // fail.
    val metrikWindowMs: Long,
    val tracy: AgentEndpoint?,
    val katcher: AgentEndpoint?,
) {
    val anyEnabled: Boolean get() = metrik != null || tracy != null || katcher != null
}

data class AgentEndpoint(
    val endpoint: String,
    val key: String,
)
