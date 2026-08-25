package io.konekt.feature.auth.server.domain

import io.konekt.domain.Currency
import io.konekt.domain.KonektException
import io.konekt.domain.Money
import io.konekt.domain.suspendRunCatching
import io.konekt.time.KonektClock

// Check a code and, if it is right, hand back a session.
//
// This is where a subscriber comes into existence: the first correct code for a number creates the
// subscriber and their account together. Doing it here rather than at request time is what keeps
// RequestOtpUseCase from being an enumeration oracle, and doing both in one repository call is what
// keeps a subscriber without an account from existing at all.
class VerifyOtpUseCase(
    private val challenges: OtpRepository,
    private val subscribers: SubscriberRepository,
    private val hasher: CodeHasher,
    private val sessions: SessionIssuer,
    private val clock: KonektClock,
    private val policy: OtpPolicy = OtpPolicy(),
) {
    suspend operator fun invoke(params: Params): Result<Session> =
        suspendRunCatching {
            val msisdn = Msisdn.parse(params.msisdn)
            val now = clock.now()

            val challenge =
                challenges.find(msisdn)
                    // No outstanding code. The same answer as a wrong one, and the same as for a
                    // number nobody has ever requested a code for: all three are "that code is not
                    // right", because any other wording is a question about the number that anybody
                    // may ask.
                    ?: throw wrongCode()

            if (challenge.isLocked(now)) {
                throw KonektException.RateLimited(
                    (challenge.lockedUntil!! - now).inWholeSeconds.coerceAtLeast(1),
                )
            }

            if (challenge.isExpired(now)) {
                // Distinguished from a wrong code on purpose. It tells the subscriber what to DO —
                // ask for another — and it reveals nothing: an expired challenge exists for any
                // number that requested one, known or not.
                throw KonektException.Validation("code", "that code has expired, ask for a new one")
            }

            if (hasher.hash(params.code) != challenge.codeHash) {
                val used = challenge.attemptsUsed + 1
                val lockUntil = if (used >= policy.maxAttempts) now + policy.lockout else null
                challenges.recordFailedAttempt(msisdn, lockUntil)

                if (lockUntil != null) {
                    throw KonektException.RateLimited(policy.lockout.inWholeSeconds)
                }
                throw wrongCode()
            }

            // Single use. Consumed before the session is issued, so a code cannot be spent twice by
            // two requests arriving together.
            challenges.clear(msisdn)

            val subscriber =
                subscribers.findByMsisdn(msisdn)
                    ?: subscribers.createWithAccount(msisdn, Money.zero(Currency.DEFAULT))

            sessions.issue(subscriber)
        }

    private fun wrongCode() = KonektException.Validation("code", "that code is not right")

    data class Params(
        val msisdn: String,
        val code: String,
    )
}
