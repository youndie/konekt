package io.konekt.feature.auth.server.data

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.konekt.feature.auth.server.domain.RefreshClaims
import io.konekt.feature.auth.server.domain.TokenMinter
import io.konekt.time.KonektClock
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    // Short, and the number is a trade rather than a habit. Logout is enforced on every request
    // through the family, so this is not the revocation window — it is how often a client has to
    // rotate, and rotation is what makes a stolen refresh token detectable.
    val accessTtl: Duration = 15.minutes,
    val refreshTtl: Duration = 30.days,
)

// The claim that separates the two tokens. Without it a refresh token is an access token with a
// longer life, and a stolen refresh token is a month of access rather than one exchange that gets
// noticed.
const val TOKEN_TYPE_CLAIM = "typ"

// The family a token belongs to, carried in the ACCESS token as well so that the authentication
// provider can refuse a revoked one. This is the claim that makes logout mean anything.
const val FAMILY_CLAIM = "fam"
const val ACCESS_TOKEN = "access"
const val REFRESH_TOKEN = "refresh"

class JwtTokenMinter(
    private val config: JwtConfig,
    private val clock: KonektClock,
) : TokenMinter {
    private val algorithm = Algorithm.HMAC256(config.secret)

    override fun mintAccess(
        subscriberId: String,
        familyId: String,
    ): String = token(subscriberId, familyId, ACCESS_TOKEN, clock.now() + config.accessTtl, tokenId = null)

    override fun mintRefresh(
        subscriberId: String,
        familyId: String,
        tokenId: String,
    ): TokenMinter.RefreshTokenAndExpiry {
        val expiresAt = clock.now() + config.refreshTtl
        return TokenMinter.RefreshTokenAndExpiry(
            token = token(subscriberId, familyId, REFRESH_TOKEN, expiresAt, tokenId),
            expiresAt = expiresAt,
        )
    }

    override fun readRefresh(token: String): RefreshClaims? =
        try {
            // A verifier that insists on the refresh type. An ACCESS token presented here fails the
            // claim check and comes back null, which is the whole reason the type claim exists.
            val payload = refreshVerifier(config).verify(token)
            val subject = payload.subject ?: return null
            val family = payload.getClaim(FAMILY_CLAIM).asString() ?: return null
            val id = payload.id ?: return null
            RefreshClaims(subscriberId = subject, familyId = family, tokenId = id)
        } catch (refused: JWTVerificationException) {
            // Signature, issuer, audience, expiry and type, all of them, and none worth telling apart
            // for the caller: every one means "not a refresh token this server will honour".
            null
        }

    private fun token(
        subscriberId: String,
        familyId: String,
        type: String,
        expiresAt: Instant,
        tokenId: String?,
    ): String =
        JWT
            .create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(subscriberId)
            .withClaim(TOKEN_TYPE_CLAIM, type)
            .withClaim(FAMILY_CLAIM, familyId)
            .apply { tokenId?.let { withJWTId(it) } }
            .withExpiresAt(Date(expiresAt.toEpochMilliseconds()))
            .sign(algorithm)

    companion object {
        // Refuses a refresh token where an access token is required, which is the point of the claim.
        // Ktor's JWT plugin verifies signature, issuer, audience and expiry; the type is ours to
        // insist on.
        fun accessVerifier(config: JwtConfig): JWTVerifier = verifier(config, ACCESS_TOKEN)

        fun refreshVerifier(config: JwtConfig): JWTVerifier = verifier(config, REFRESH_TOKEN)

        private fun verifier(
            config: JwtConfig,
            type: String,
        ): JWTVerifier =
            JWT
                .require(Algorithm.HMAC256(config.secret))
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .withClaim(TOKEN_TYPE_CLAIM, type)
                .build()
    }
}
