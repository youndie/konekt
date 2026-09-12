package io.konekt

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigPair
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.Configuration
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.config.systemEnvironment
import io.konekt.db.DatabaseConfig
import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.purchase.server.data.MockPaymentGateway
import io.konekt.feature.theme.shared.api.BrandTheme
import io.konekt.observability.AgentEndpoint
import io.konekt.observability.ObservabilityConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// EVERY VARIABLE THIS PROCESS READS, DECLARED IN ONE PLACE, UNDER ONE PREFIX (`konekt#35`).
//
// What stood here was eighteen `System.getenv` calls spread over three files, and the three defects
// that shape is made of were all present:
//
//   * **A misspelled variable was silent.** `SIMULATE_TRAFIC` in a compose file is a stand that does
//     not simulate, with nothing anywhere saying so. The schema's unknown-variable check now refuses
//     to start and names the declared variable it is probably a misspelling of.
//   * **A value that did not parse fell back.** `PAYMENT_MOCK_DELAY_MS=1s` was zero, and
//     `SIMULATED_ARRIVAL_AFTER_SECONDS=ten` was ninety. Both are now refusals that name the variable
//     and what it held.
//   * **Nothing could say what a deployment thought it was configured as.** `--print-config` prints
//     it, including when the configuration will not start — which is the case it exists for.
//
// ONE SCHEMA AND NOT THREE, and one read rather than two. `ObservabilityConfig.fromEnv()` used to
// read the environment a second time, from inside `Application.module`; a process that reads its
// configuration twice is one that can be half-configured at the second read, and it reported its
// problems one file at a time. `ConfigurationException` collects all of them, because a deployment
// being configured for the first time has several and fixing them one restart each is the cost.
object KonektSchema {
    val PORT: ConfigKey<Int> = ConfigKey.int("PORT", default = 8080)

    val DB_URL: ConfigKey<String> = ConfigKey.required("DB_URL")
    val DB_USER: ConfigKey<String> = ConfigKey.required("DB_USER")
    val DB_PASSWORD: ConfigKey<String> = ConfigKey.secret("DB_PASSWORD")

    // TEN, and it is read for the first time here. The variable existed — `DatabaseConfig.fromEnv`
    // read it — and `DatabaseConfig.fromEnv` had no callers: `KonektConfig` built the three-argument
    // constructor and took the Kotlin default. So `scripts/measure/crac-restore.sh` phase h8, which
    // exists to restore a checkpoint with a pool of one, had been varying a number the process
    // ignored. Declared here, it is the process's pool size for the first time.
    val DB_POOL_SIZE: ConfigKey<Int> = ConfigKey.int("DB_POOL_SIZE", default = 10)

    val JWT_SECRET: ConfigKey<String> = ConfigKey.secret("JWT_SECRET")
    val JWT_ISSUER: ConfigKey<String> = ConfigKey.string("JWT_ISSUER", default = "konekt")
    val JWT_AUDIENCE: ConfigKey<String> = ConfigKey.string("JWT_AUDIENCE", default = "konekt-app")

    // Opt in by the exact string "true", which is kore's rule 6 and was already this repository's:
    // an unset or misspelled switch means CLOSED. A machine route that reveals any subscriber's
    // one-time code IS the authentication system if it ships.
    val DEV_REVEAL_OTP: ConfigKey<Boolean> = ConfigKey.boolean("DEV_REVEAL_OTP")

    // Whether the development screens exist — today one, which sends a component no client can
    // render. Separate from the OTP readback rather than folded into it: a test environment might
    // reasonably want one without the other.
    val DEV_SCREENS: ConfigKey<Boolean> = ConfigKey.boolean("DEV_SCREENS")

    val BROKER_HOST: ConfigKey<String> = ConfigKey.string("BROKER_HOST", default = "broker")
    val BROKER_PORT: ConfigKey<Int> = ConfigKey.int("BROKER_PORT", default = 9092)

    // APPROVE unless told otherwise, so a deployment that forgets to set it cannot be one that
    // declines everything.
    val PAYMENT_MOCK_MODE: ConfigKey<String> = ConfigKey.string("PAYMENT_MOCK_MODE", default = "approve")
    val PAYMENT_MOCK_DELAY_MS: ConfigKey<Duration> = ConfigKey.millis("PAYMENT_MOCK_DELAY_MS", default = Duration.ZERO)

    // OFF unless an explicit "true": it publishes fictional usage against real counters, and a
    // deployment that forgot to set it must not be one that quietly spends its subscribers'
    // allowances.
    val SIMULATE_TRAFFIC: ConfigKey<Boolean> = ConfigKey.boolean("SIMULATE_TRAFFIC")

    // SECONDS, and the name says so, because kore's only duration key is milliseconds and renaming
    // this to `_MS` would change the unit under every deployment that already sets it. A `long` plus
    // `.seconds` keeps the number a person writes the number they wrote before.
    //
    // Ninety is the demonstration's answer — long enough to show the dormant card and say what it
    // means — and the compose stand overrides it with ten, because an end-to-end scenario must not
    // sleep for a minute and a half.
    val SIMULATED_ARRIVAL_AFTER_SECONDS: ConfigKey<Long> =
        ConfigKey.long("SIMULATED_ARRIVAL_AFTER_SECONDS", default = 90)

    // Defaulted rather than required: an operator who has not chosen still gets a coherent
    // application, and `brand-a` is the one the design canvas is drawn in — so an unconfigured
    // deployment looks like the product rather than like a fallback.
    val BRAND: ConfigKey<String> = ConfigKey.string("BRAND", default = BrandTheme.DEFAULT_BRAND)

    // Apply the migrations and exit, without serving. The deploy runs the same image this way before
    // the application pods roll.
    val MIGRATE_ONLY: ConfigKey<Boolean> = ConfigKey.boolean("MIGRATE_ONLY")

    // The service name IS the identifier in all three agents — there is no registration step
    // anywhere — so a typo does not fail, it creates a phantom service that looks healthy and
    // receives nothing.
    val OBSERVABILITY_SERVICE: ConfigKey<String> = ConfigKey.string("OBSERVABILITY_SERVICE", default = "konekt-server")

    // A release that changes draws a deploy marker in metrik and names the build in a katcher crash
    // group. `Unspecified` is katcher's own default and it is what makes a crash unactionable.
    val RELEASE: ConfigKey<String> = ConfigKey.string("RELEASE", default = "dev")
    val ENVIRONMENT: ConfigKey<String> = ConfigKey.string("ENVIRONMENT", default = "dev")

    // SIXTY THOUSAND, read out of `MetrikConfig.<init>` in the published `metrik:agent-jvm:0.2.18` —
    // `ldc2_w 60000l; putfield windowMs` — rather than recalled. It used to be a `Long?` that was
    // left unset, so the agent's own default applied and nothing here stated it; kore's schema has no
    // optional number, and pinning metrik's value is the change that keeps the behaviour identical
    // while making it visible in `--print-config`.
    //
    // Why it matters at all: the agent buffers an aggregation window and sends it when the window
    // closes, so a freshly started process reports nothing for a minute. That is right for a
    // deployment and wrong for a stand, where the whole run is shorter than one window — leaving it
    // at the default is what made an e2e check pass locally against a stand that had been up a while
    // and fail in CI against a fresh one.
    val METRIK_WINDOW_MS: ConfigKey<Long> = ConfigKey.long("METRIK_WINDOW_MS", default = 60_000)

    val METRIK_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("METRIK_ENDPOINT")
    val METRIK_KEY: ConfigKey<String?> = ConfigKey.optional("METRIK_KEY", secret = true)
    val TRACY_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("TRACY_ENDPOINT")
    val TRACY_KEY: ConfigKey<String?> = ConfigKey.optional("TRACY_KEY", secret = true)
    val KATCHER_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("KATCHER_ENDPOINT")
    val KATCHER_KEY: ConfigKey<String?> = ConfigKey.optional("KATCHER_KEY", secret = true)

    // THE PREFIX, and it is part of the schema rather than decoration: it is what scopes the
    // unknown-variable check, which over a whole container environment would fail on `PATH` and
    // `HOSTNAME` on its first deployment and be switched off forever.
    //
    // It also collides with something, and the collision is why `enableServiceLinks: false` is in the
    // chart. The kubelet injects a block of variables for every Service in the namespace, named after
    // the SERVICE — and this chart's server Service is `{{ .Release.Name }}`, which is `konekt`. So a
    // pod in that namespace is handed `KONEKT_PORT=tcp://10.43.x.x:8080` along with `KONEKT_SERVICE_HOST`
    // and four `KONEKT_PORT_8080_TCP*` names, every one of them under this prefix. `KONEKT_PORT` is
    // declared here and would not parse; the rest are undeclared and would each be a refusal.
    val SCHEMA: ConfigSchema =
        ConfigSchema(
            prefix = "KONEKT",
            keys =
                listOf(
                    PORT,
                    DB_URL,
                    DB_USER,
                    DB_PASSWORD,
                    DB_POOL_SIZE,
                    JWT_SECRET,
                    JWT_ISSUER,
                    JWT_AUDIENCE,
                    DEV_REVEAL_OTP,
                    DEV_SCREENS,
                    BROKER_HOST,
                    BROKER_PORT,
                    PAYMENT_MOCK_MODE,
                    PAYMENT_MOCK_DELAY_MS,
                    SIMULATE_TRAFFIC,
                    SIMULATED_ARRIVAL_AFTER_SECONDS,
                    BRAND,
                    MIGRATE_ONLY,
                    OBSERVABILITY_SERVICE,
                    RELEASE,
                    ENVIRONMENT,
                    METRIK_WINDOW_MS,
                    METRIK_ENDPOINT,
                    METRIK_KEY,
                    TRACY_ENDPOINT,
                    TRACY_KEY,
                    KATCHER_ENDPOINT,
                    KATCHER_KEY,
                ),
            // AN AGENT IS BOTH VARIABLES OR NEITHER, and this replaces a hand-written check that said
            // the same thing in `ObservabilityConfig`. Every one of the three agents answers a missing
            // endpoint or a missing key by doing nothing — metrik's plugin has an `enabled` flag,
            // tracy's delivery never connects, katcher's `start` prints a line and returns — so a
            // half-configured agent is a deployment that believes it is observed and is silent.
            pairs =
                listOf(
                    ConfigPair("METRIK_ENDPOINT", "METRIK_KEY"),
                    ConfigPair("TRACY_ENDPOINT", "TRACY_KEY"),
                    ConfigPair("KATCHER_ENDPOINT", "KATCHER_KEY"),
                ),
        )
}

// Everything the process needs from its environment, read once at startup so a missing value is a
// process that will not start rather than a route that fails later under a user.
data class KonektConfig(
    val port: Int,
    val database: DatabaseConfig,
    val jwt: JwtConfig,
    // Whether the development endpoint that reads back a one-time code exists. Default false, and the
    // default is the security property — see `KonektSchema.DEV_REVEAL_OTP`.
    val revealOtpCodes: Boolean,
    val brokerHost: String,
    val brokerPort: Int,
    val paymentMode: MockPaymentGateway.Mode,
    val paymentDelay: Duration,
    val simulateTraffic: Boolean,
    val simulatedArrivalAfter: Duration,
    val brand: String,
    val devScreens: Boolean,
    val migrateOnly: Boolean,
    // READ WITH EVERYTHING ELSE, in the same pass and against the same schema. It used to be read
    // separately, from inside `Application.module`, which meant the composition root could refuse a
    // configuration the entry point had already accepted.
    val observability: ObservabilityConfig,
) {
    companion object {
        // Long enough to look at a dormant card and say what it means; short enough that nobody
        // watching gives up. See `KonektSchema.SIMULATED_ARRIVAL_AFTER_SECONDS`.
        val DEFAULT_SIMULATED_ARRIVAL_AFTER: Duration = 90.seconds

        fun fromEnv(environment: Environment = systemEnvironment()): KonektConfig =
            of(KonektSchema.SCHEMA.read(environment))

        fun of(values: Configuration): KonektConfig =
            KonektConfig(
                port = values[KonektSchema.PORT],
                database =
                    DatabaseConfig(
                        url = values[KonektSchema.DB_URL],
                        user = values[KonektSchema.DB_USER],
                        password = values[KonektSchema.DB_PASSWORD],
                        maximumPoolSize = values[KonektSchema.DB_POOL_SIZE],
                    ),
                jwt =
                    JwtConfig(
                        secret = values[KonektSchema.JWT_SECRET],
                        issuer = values[KonektSchema.JWT_ISSUER],
                        audience = values[KonektSchema.JWT_AUDIENCE],
                    ),
                revealOtpCodes = values[KonektSchema.DEV_REVEAL_OTP],
                brokerHost = values[KonektSchema.BROKER_HOST],
                brokerPort = values[KonektSchema.BROKER_PORT],
                // Anything that is not "decline" approves, which is the same rule the environment
                // read had. A misspelled mode is therefore a stand that approves rather than one that
                // refuses every purchase — the safe direction for a mock whose job is to let the
                // happy path through.
                paymentMode =
                    when (values[KonektSchema.PAYMENT_MOCK_MODE]) {
                        "decline" -> MockPaymentGateway.Mode.DECLINE
                        else -> MockPaymentGateway.Mode.APPROVE
                    },
                paymentDelay = values[KonektSchema.PAYMENT_MOCK_DELAY_MS],
                simulateTraffic = values[KonektSchema.SIMULATE_TRAFFIC],
                simulatedArrivalAfter = values[KonektSchema.SIMULATED_ARRIVAL_AFTER_SECONDS].seconds,
                brand = values[KonektSchema.BRAND],
                devScreens = values[KonektSchema.DEV_SCREENS],
                migrateOnly = values[KonektSchema.MIGRATE_ONLY],
                observability =
                    ObservabilityConfig(
                        service = values[KonektSchema.OBSERVABILITY_SERVICE],
                        release = values[KonektSchema.RELEASE],
                        environment = values[KonektSchema.ENVIRONMENT],
                        metrik = agent(values, KonektSchema.METRIK_ENDPOINT, KonektSchema.METRIK_KEY),
                        metrikWindowMs = values[KonektSchema.METRIK_WINDOW_MS],
                        tracy = agent(values, KonektSchema.TRACY_ENDPOINT, KonektSchema.TRACY_KEY),
                        katcher = agent(values, KonektSchema.KATCHER_ENDPOINT, KonektSchema.KATCHER_KEY),
                    ),
            )

        // Both or neither, and the SCHEMA is what refuses the half — this only has to read the whole
        // one. The `!!` is safe for exactly that reason: `ConfigPair` has already turned one-without-
        // the-other into a `ConfigurationException` naming the missing variable.
        private fun agent(
            values: Configuration,
            endpoint: ConfigKey<String?>,
            key: ConfigKey<String?>,
        ): AgentEndpoint? = values[endpoint]?.let { AgentEndpoint(endpoint = it, key = values[key]!!) }
    }
}
