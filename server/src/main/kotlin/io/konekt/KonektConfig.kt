package io.konekt

import io.konekt.db.DatabaseConfig
import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.purchase.server.data.MockPaymentGateway
import io.konekt.feature.theme.shared.api.BrandTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Everything the process needs from its environment, read once at startup so a missing value is a
// process that will not start rather than a route that fails later under a user.
data class KonektConfig(
    val port: Int,
    val database: DatabaseConfig,
    val jwt: JwtConfig,
    // Whether the development endpoint that reads back a one-time code exists. Default false, and the
    // default is the security property: a machine route that reveals any subscriber's code IS the
    // authentication system if it ships.
    val revealOtpCodes: Boolean,
    val brokerHost: String,
    val brokerPort: Int,
    // How the payment provider behaves. APPROVE unless told otherwise, so a deployment that forgets
    // to set it cannot be one that declines everything.
    val paymentMode: MockPaymentGateway.Mode,
    val paymentDelay: Duration,
    // Whether the traffic simulator runs. OFF unless an explicit "true", like every other switch
    // here: it publishes fictional usage against real counters, and a deployment that forgot to set
    // it must not be one that quietly spends its subscribers' allowances.
    val simulateTraffic: Boolean,
    // Apply the migrations and exit, without serving. The deploy runs the same image this way before
    // the application pods roll.
    // Whether the development screens exist — today one, which sends a component no client can
    // render. Separate from `revealOtpCodes` rather than folded into it: they are two different
    // decisions with two different consequences, and a deployment might reasonably want the OTP
    // readback in a test environment without a demonstration screen in its route table.
    // Which brand kit this deployment serves. Defaulted rather than required: an operator who has not
    // chosen still gets a coherent application, and `brand-a` is the one the design canvas is drawn
    // in — so an unconfigured deployment looks like the product rather than like a fallback.
    val brand: String,
    val devScreens: Boolean,
    val migrateOnly: Boolean,
) {
    companion object {
        fun fromEnv(): KonektConfig =
            KonektConfig(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                database =
                    DatabaseConfig(
                        url = require("DB_URL"),
                        user = require("DB_USER"),
                        password = require("DB_PASSWORD"),
                    ),
                jwt =
                    JwtConfig(
                        secret = require("JWT_SECRET"),
                        issuer = System.getenv("JWT_ISSUER") ?: "konekt",
                        audience = System.getenv("JWT_AUDIENCE") ?: "konekt-app",
                    ),
                // Opt in by an explicit "true", so an unset or misspelled variable means closed. An
                // absent setting must never mean open.
                revealOtpCodes = System.getenv("DEV_REVEAL_OTP") == "true",
                brokerHost = System.getenv("BROKER_HOST") ?: "broker",
                brokerPort = System.getenv("BROKER_PORT")?.toIntOrNull() ?: 9092,
                paymentMode =
                    when (System.getenv("PAYMENT_MOCK_MODE")) {
                        "decline" -> MockPaymentGateway.Mode.DECLINE
                        else -> MockPaymentGateway.Mode.APPROVE
                    },
                paymentDelay = (System.getenv("PAYMENT_MOCK_DELAY_MS")?.toLongOrNull() ?: 0L).milliseconds,
                simulateTraffic = System.getenv("SIMULATE_TRAFFIC") == "true",
                brand = System.getenv(BrandTheme.BRAND_ENV) ?: BrandTheme.DEFAULT_BRAND,
                devScreens = System.getenv("DEV_SCREENS") == "true",
                migrateOnly = System.getenv("MIGRATE_ONLY") == "true",
            )

        private fun require(name: String): String =
            System.getenv(name) ?: error("$name is not set — the server cannot start without it")
    }
}
