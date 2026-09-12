package io.konekt

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.Configuration
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.config.systemEnvironment
import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.observability.ObservabilityKeys
import io.github.youndie.kore.observability.ObservabilitySettings
import io.github.youndie.kore.version.KoreKeys
import io.github.youndie.kore.version.KoreRelease
import io.github.youndie.kore.version.releaseOf
import io.konekt.db.DatabaseConfig
import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.purchase.server.data.MockPaymentGateway
import io.konekt.feature.theme.shared.api.BrandTheme
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

    // THE OBSERVABILITY VARIABLES ARE KORE'S OWN KEYS, spliced into this schema rather than
    // declared here (`konekt#35`). kore publishes them as a LIST and not as a schema, deliberately:
    // a schema owns a prefix and the unknown-variable refusal is scoped to it, so two schemas would
    // mean two scopes and a variable that is unknown to one and declared by the other.
    //
    // What that costs, and it is a rename in a chart: `KONEKT_OBSERVABILITY_SERVICE` becomes
    // `KONEKT_SERVICE`. What it buys is three settings konekt did not have — `TRACY_SAMPLE_RATE`,
    // `INSTANCE` and `KATCHER_CACHE_DIR` — and one it loses, below.
    //
    // `METRIK_WINDOW_MS` IS KORE'S KEY TOO, as of 0.1.4 — it was konekt's for one version, and the
    // reason is worth keeping: kore's wiring could not set metrik's aggregation window, metrik's
    // default is 60 seconds, and `ObservabilityScenarioTest` waits 20 for a request to be counted.
    // Measured, and the first measurement was wrong: at the default the scenario passed four times
    // and then failed on a freshly rebuilt stand, server up 41 seconds. Four passes measured a stand
    // that had been up for minutes, not the setting. `youndie/kore#68` closed it and the variable
    // name did not change, so no deployment moves.

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
                    // `/version` reduced to the release name alone. kore's key, like the agents'.
                    KoreKeys.VERSION_REDUCED,
                ) + ObservabilityKeys.all,
            // AN AGENT IS BOTH VARIABLES OR NEITHER — kore's rule now, and it used to be a
            // hand-written check in `ObservabilityConfig` that threw on the FIRST bad agent. Every
            // one of the three answers a missing endpoint or a missing key by doing nothing, so a
            // half-configured agent is a deployment that believes it is observed and is silent.
            pairs = ObservabilityKeys.pairs,
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
    val observability: ObservabilitySettings,
    // KONEKT'S, NOT KORE'S, and `Observability.kt` says why: kore's wiring cannot set it, so metrik
    // is the one agent this server still installs itself (youndie/kore#68).
    // WHICH BUILD THIS IS, and it is ONE value rather than two. The compiled-in identity is the
    // source and `KONEKT_RELEASE` overrides it; the same `KoreRelease` names the metrik deploy
    // marker, the katcher crash group and the `/version` body, so a disagreement between what is
    // deployed and what is reported becomes a `compiled-release:` line instead of nothing at all.
    //
    // It also means the agents always have a release. kore refuses to start an agent without one —
    // katcher's own default is `Unspecified`, which makes a crash unactionable — and that refusal is
    // now unreachable: `version+commit` is always there to fall back to.
    val release: KoreRelease,
    // `/version` serves the release name alone. Off here: this is a public repository, a commit hash
    // in it is not a secret, and the route earns its keep in every deploy check.
    val versionReduced: Boolean,
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
                // kore reads its own keys back out of the configuration, so konekt neither names
                // them twice nor decides what "both or neither" means — it did, and the copy is gone.
                //
                // `instanceFallback` is the one thing kore cannot read: the pod name lives behind a
                // `getenv` that differs per target, so it is passed in rather than split inside the
                // library for one string. Without it every instance of a rolling deploy is the same
                // instance, and "which one is slow" stops being a question the data can answer.
                observability =
                    ObservabilitySettings.from(
                        values,
                        instanceFallback = System.getenv("HOSTNAME") ?: "local",
                    ),
                release = releaseOf(KoreBuildIdentity, override = values[ObservabilityKeys.RELEASE]),
                versionReduced = values[KoreKeys.VERSION_REDUCED],
            )
    }
}
