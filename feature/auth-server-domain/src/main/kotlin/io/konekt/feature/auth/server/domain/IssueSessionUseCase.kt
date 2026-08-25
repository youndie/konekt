package io.konekt.feature.auth.server.domain

import io.konekt.time.KonektClock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Opens a family and issues the first pair in it. It implements SessionIssuer so that
// VerifyOtpUseCase does not have to know any of this exists — signing in still means "give me a
// session".
@OptIn(ExperimentalUuidApi::class)
class IssueSessionUseCase(
    private val sessions: SessionRepository,
    private val minter: TokenMinter,
    private val clock: KonektClock,
) : SessionIssuer {
    override suspend fun issue(subscriber: Subscriber): Session {
        val now = clock.now()
        val family = sessions.openFamily(subscriber.id, now)
        return newPair(subscriberId = subscriber.id, familyId = family.id, now = now)
    }

    // Also the second half of a rotation: a refreshed pair belongs to the SAME family, which is what
    // makes a family a run of sessions rather than one session.
    suspend fun newPair(
        subscriberId: String,
        familyId: String,
        now: Instant,
    ): Session {
        val tokenId = Uuid.random().toString()
        val refresh = minter.mintRefresh(subscriberId, familyId, tokenId)

        sessions.recordRefreshToken(
            IssuedRefreshToken(
                id = tokenId,
                familyId = familyId,
                issuedAt = now,
                expiresAt = refresh.expiresAt,
                usedAt = null,
            ),
        )

        return Session(
            accessToken = minter.mintAccess(subscriberId, familyId),
            refreshToken = refresh.token,
        )
    }
}
