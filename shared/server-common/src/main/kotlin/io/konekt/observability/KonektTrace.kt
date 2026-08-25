package io.konekt.observability

import ru.workinprogress.tracy.agent.TracyAgent

// THE HANDLE A FEATURE ASKS FOR, and it is a wrapper rather than the agent itself for one reason:
// Koin cannot bind a null, and a feature must be able to run in a deployment that has no tracy.
//
// It is NOT a no-op logger. A no-op would answer every call successfully and write nothing, which is
// indistinguishable from a working agent at exactly the moment somebody goes looking for a trace that
// was never sent. Holding a nullable makes the absence visible at the call site — `trace.agent?` — and
// leaves proving that data actually arrives to the stand, which is where it belongs.
class KonektTrace(
    val agent: TracyAgent?,
) {
    fun logger(name: String) = agent?.logger(name)
}
