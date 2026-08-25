package io.konekt.feature.auth.server.data

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.konekt.feature.auth.server.domain.Session
import io.konekt.feature.auth.server.domain.SessionIssuer
import io.konekt.feature.auth.server.domain.Subscriber
import io.konekt.time.KonektClock
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    // Short, because there is no revocation: a token is valid until it expires, so the window an
    // attacker gets from a stolen one is exactly this number.
    val accessTtl: Duration = 15.minutes,
    val refreshTtl: Duration = 30.days,
)

// The claim that separates the two tokens. Without it a refresh token is an access token with a
// longer life, and a stolen refresh token is a month of access rather than a request for a new pair.
const val TOKEN_TYPE_CLAIM = "typ"
const val ACCESS_TOKEN = "access"
const val REFRESH_TOKEN = "refresh"
const val SUBSCRIBER_CLAIM = "sub"

class JwtSessionIssuer(
    private val config: JwtConfig,
    private val clock: KonektClock,
) : SessionIssuer {
    private val algorithm = Algorithm.HMAC256(config.secret)

    override fun issue(subscriber: Subscriber): Session {
        val now = clock.now()
        return Session(
            accessToken =
                token(
                    subscriber.id,
                    ACCESS_TOKEN,
                    now.toEpochMilliseconds() + config.accessTtl.inWholeMilliseconds,
                ),
            refreshToken =
                token(
                    subscriber.id,
                    REFRESH_TOKEN,
                    now.toEpochMilliseconds() + config.refreshTtl.inWholeMilliseconds,
                ),
        )
    }

    private fun token(
        subscriberId: String,
        type: String,
        expiresAtMillis: Long,
    ): String =
        JWT
            .create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(subscriberId)
            .withClaim(TOKEN_TYPE_CLAIM, type)
            .withExpiresAt(Date(expiresAtMillis))
            .sign(algorithm)

    companion object {
        // The verifier refuses a refresh token where an access token is required, which is the point
        // of the claim. Ktor's JWT plugin verifies signature, issuer, audience and expiry; the type
        // is ours to insist on.
        fun verifier(config: JwtConfig): JWTVerifier =
            JWT
                .require(Algorithm.HMAC256(config.secret))
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .withClaim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN)
                .build()
    }
}
