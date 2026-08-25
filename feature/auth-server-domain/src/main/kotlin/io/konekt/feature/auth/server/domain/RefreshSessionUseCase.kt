package io.konekt.feature.auth.server.domain

import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching
import io.konekt.time.KonektClock

// Exchange a refresh token for a new pair, and notice when one is used twice.
//
// ROTATION IS WHAT MAKES THEFT DETECTABLE. Without it a stolen refresh token is a month of quiet
// access. With it, the thief and the subscriber both hold a token, and whichever of them exchanges
// second presents one that has already been used — which cannot happen to an honest holder, because
// an honest holder replaced theirs the moment they used it.
//
// Which of the two is the thief is unknowable from here, so the answer is to end the family. That
// costs an honest subscriber one sign-in and costs a thief everything, which is the right way round.
class RefreshSessionUseCase(
    private val sessions: SessionRepository,
    private val minter: TokenMinter,
    private val issue: IssueSessionUseCase,
    private val clock: KonektClock,
) {
    suspend operator fun invoke(refreshToken: String): Result<Session> =
        suspendRunCatching {
            val now = clock.now()

            // Null for a token this server did not sign, one that has expired, and — the case the
            // type claim exists for — an ACCESS token presented here.
            val claims = minter.readRefresh(refreshToken) ?: throw KonektException.Unauthorized()

            val family = sessions.findFamily(claims.familyId) ?: throw KonektException.Unauthorized()
            if (!family.isActive) throw KonektException.Unauthorized("this session has ended")

            val stored = sessions.findRefreshToken(claims.tokenId) ?: throw KonektException.Unauthorized()

            // The arbitration happens in the database, not here. Two exchanges of one token arriving
            // together would both pass an if-then-write in Kotlin; only one can win a conditional
            // UPDATE. The loser is treated as reuse, which is correct — from the server's side a
            // race and a theft look identical, and the safe reading is the second one.
            if (!sessions.markRefreshTokenUsed(stored.id, now)) {
                sessions.revokeFamily(family.id, SessionFamily.REVOKED_BY_REUSE, now)
                throw KonektException.Unauthorized("this session has ended")
            }

            if (stored.expiresAt <= now) throw KonektException.Unauthorized()

            issue.newPair(subscriberId = claims.subscriberId, familyId = family.id, now = now)
        }
}
