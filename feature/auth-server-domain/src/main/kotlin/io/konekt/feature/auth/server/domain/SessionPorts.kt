package io.konekt.feature.auth.server.domain

import kotlin.time.Instant

// A run of sessions descending from one sign-in. Rotation replaces the token inside a family; logout
// and a detected theft end the family itself.
//
// The family exists because a JWT cannot be taken back. Ending one session means ending something the
// server can look at, and this is that something.
data class SessionFamily(
    val id: String,
    val subscriberId: String,
    val revokedAt: Instant?,
    val revokedReason: String?,
) {
    val isActive: Boolean get() = revokedAt == null

    companion object {
        const val REVOKED_BY_LOGOUT = "logout"

        // A refresh token used twice means one of the two holders is not the subscriber. Which one
        // is unknowable, so both lose the family.
        const val REVOKED_BY_REUSE = "reuse_detected"
    }
}

data class IssuedRefreshToken(
    val id: String,
    val familyId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    // Set the moment it is exchanged. A second exchange of the same token is what "used twice" means,
    // and it is the signal this whole design exists to notice.
    val usedAt: Instant?,
)

interface SessionRepository {
    suspend fun openFamily(
        subscriberId: String,
        at: Instant,
    ): SessionFamily

    suspend fun findFamily(id: String): SessionFamily?

    suspend fun recordRefreshToken(token: IssuedRefreshToken)

    suspend fun findRefreshToken(id: String): IssuedRefreshToken?

    // Returns whether THIS call was the one that marked it. Two exchanges of one token arriving
    // together must not both succeed, and the database is the only place that can arbitrate — an
    // if-then-write in Kotlin lets both through.
    suspend fun markRefreshTokenUsed(
        id: String,
        at: Instant,
    ): Boolean

    suspend fun revokeFamily(
        id: String,
        reason: String,
        at: Instant,
    )
}

// What a token carries. Read back from a token rather than trusted from a request body.
data class RefreshClaims(
    val subscriberId: String,
    val familyId: String,
    val tokenId: String,
)

// Minting and reading tokens, with no idea that families are stored anywhere. The JWT is the data
// layer's business; whether a session is still alive is the domain's.
interface TokenMinter {
    fun mintAccess(
        subscriberId: String,
        familyId: String,
    ): String

    fun mintRefresh(
        subscriberId: String,
        familyId: String,
        tokenId: String,
    ): RefreshTokenAndExpiry

    // Null for a token this server did not issue, one that has expired, or an ACCESS token presented
    // where a refresh token belongs. The last is the reason the two carry a type claim at all.
    fun readRefresh(token: String): RefreshClaims?

    data class RefreshTokenAndExpiry(
        val token: String,
        val expiresAt: Instant,
    )
}
