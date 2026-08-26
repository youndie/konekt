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
data class ObservabilityConfig(
    // The service name IS the identifier in all three — there is no registration step anywhere — so a
    // typo does not fail, it creates a phantom service that looks healthy and receives nothing.
    val service: String,
    // A release that changes draws a deploy marker in metrik and names the build in a katcher crash
    // group. `Unspecified` is katcher's own default and it is what makes a crash unactionable.
    val release: String,
    val environment: String,
    val metrik: AgentEndpoint?,
    // THE AGENT'S AGGREGATION WINDOW, and it is here because its default is SIXTY SECONDS — read in
    // `metrik/shared/.../Protocol.kt`, not recalled. The agent buffers a window and sends it when the
    // window closes, so a freshly started process reports nothing at all for a minute.
    //
    // That is right for a deployment and wrong for a stand, where the whole run is shorter than one
    // window. Left at the default it made the e2e check pass locally against a stand that had been up
    // for a while and fail in CI against a fresh one — a result measured where the mechanism could not
    // fail.
    val metrikWindowMs: Long?,
    val tracy: AgentEndpoint?,
    val katcher: AgentEndpoint?,
) {
    val anyEnabled: Boolean get() = metrik != null || tracy != null || katcher != null

    companion object {
        fun fromEnv(): ObservabilityConfig =
            ObservabilityConfig(
                service = System.getenv("OBSERVABILITY_SERVICE") ?: "konekt-server",
                release = System.getenv("RELEASE") ?: "dev",
                environment = System.getenv("ENVIRONMENT") ?: "dev",
                metrik = endpoint("METRIK"),
                metrikWindowMs = System.getenv("METRIK_WINDOW_MS")?.toLongOrNull(),
                tracy = endpoint("TRACY"),
                katcher = endpoint("KATCHER"),
            )

        // `<NAME>_ENDPOINT` and `<NAME>_KEY`, together or not at all.
        private fun endpoint(name: String): AgentEndpoint? {
            val endpoint = System.getenv("${name}_ENDPOINT")?.takeIf { it.isNotBlank() }
            val key = System.getenv("${name}_KEY")?.takeIf { it.isNotBlank() }

            return when {
                endpoint == null && key == null -> {
                    null
                }

                endpoint == null || key == null -> {
                    error(
                        "${name}_ENDPOINT and ${name}_KEY must be set together — " +
                            "one without the other is a deployment that believes it is observed and is not",
                    )
                }

                else -> {
                    AgentEndpoint(endpoint, key)
                }
            }
        }
    }
}

data class AgentEndpoint(
    val endpoint: String,
    val key: String,
)
