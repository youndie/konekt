package io.konekt

import io.konekt.db.DatabaseConfig
import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.purchase.server.data.MockPaymentGateway
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
    // How the payment provider behaves. APPROVE unless told otherwise, so a deployment that forgets
    // to set it cannot be one that declines everything.
    val paymentMode: MockPaymentGateway.Mode,
    val paymentDelay: Duration,
    // Apply the migrations and exit, without serving. The deploy runs the same image this way before
    // the application pods roll.
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
                paymentMode =
                    when (System.getenv("PAYMENT_MOCK_MODE")) {
                        "decline" -> MockPaymentGateway.Mode.DECLINE
                        else -> MockPaymentGateway.Mode.APPROVE
                    },
                paymentDelay = (System.getenv("PAYMENT_MOCK_DELAY_MS")?.toLongOrNull() ?: 0L).milliseconds,
                migrateOnly = System.getenv("MIGRATE_ONLY") == "true",
            )

        private fun require(name: String): String =
            System.getenv(name) ?: error("$name is not set — the server cannot start without it")
    }
}
