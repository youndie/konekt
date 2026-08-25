package io.konekt.feature.auth.server.domain

import io.konekt.domain.suspendRunCatching
import io.konekt.time.KonektClock

// Ends the family the caller is signed in under.
//
// It works on the ACCESS token too, and that is not free: a JWT is valid until it expires, so making
// logout mean anything requires the server to look at something on every request. The access token
// therefore carries its family id, and the authentication provider refuses a principal whose family
// has been revoked. The price is one indexed lookup per authenticated request — stated here because
// it is the kind of cost that is chosen once and paid forever.
//
// The alternative is a short access lifetime and a logout that takes effect when it expires. That is
// what "stateless logout" always means, and it is worth naming rather than implying.
class LogoutUseCase(
    private val sessions: SessionRepository,
    private val clock: KonektClock,
) {
    suspend operator fun invoke(familyId: String): Result<Unit> =
        suspendRunCatching {
            // Idempotent by construction: revoking an already-revoked family leaves the first
            // revocation's reason and time in place, so a logout cannot overwrite the record of a
            // detected theft.
            sessions.revokeFamily(familyId, SessionFamily.REVOKED_BY_LOGOUT, clock.now())
        }
}
