package io.konekt.feature.auth.server.domain

import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.auth.shared.api.RequestOtpResponse
import io.konekt.time.KonektClock

// Issue a code for a number.
//
// THE ONE PROPERTY THIS HAS TO HAVE: the answer must not depend on whether the number is known.
// It is true here by construction rather than by care — nothing in this use case looks a subscriber
// up. A subscriber row is created on the first successful VERIFY, not on request, precisely so that
// requesting a code cannot enumerate anybody: no lookup, no branch, no difference in the work done,
// so no difference in the timing either.
class RequestOtpUseCase(
    private val challenges: OtpRepository,
    private val codes: CodeGenerator,
    private val hasher: CodeHasher,
    private val delivery: OtpDelivery,
    private val clock: KonektClock,
    private val policy: OtpPolicy = OtpPolicy(),
) {
    suspend operator fun invoke(rawMsisdn: String): Result<RequestOtpResponse> =
        suspendRunCatching {
            val msisdn = Msisdn.parse(rawMsisdn)
            val now = clock.now()
            val existing = challenges.find(msisdn)

            // A lockout survives a resend. Otherwise "send again" is a reset button on the attempt
            // counter, and the six-attempt budget becomes six attempts per button press.
            existing?.lockedUntil?.let { until ->
                if (now < until) {
                    throw KonektException.RateLimited((until - now).inWholeSeconds.coerceAtLeast(1))
                }
            }

            // Throttle the resend itself, or the SMS bill is a stranger's to write.
            existing?.let { challenge ->
                val nextAllowed = challenge.issuedAt + policy.resendAfter
                if (now < nextAllowed) {
                    throw KonektException.RateLimited((nextAllowed - now).inWholeSeconds.coerceAtLeast(1))
                }
            }

            val code = codes.generate(policy.codeLength)
            challenges.put(
                OtpChallenge(
                    msisdn = msisdn,
                    codeHash = hasher.hash(code),
                    issuedAt = now,
                    expiresAt = now + policy.ttl,
                    // A fresh code starts a fresh budget. The lockout above is what stops that from
                    // being a loophole.
                    attemptsUsed = 0,
                    lockedUntil = null,
                ),
            )

            delivery.deliver(msisdn, code)

            RequestOtpResponse(
                codeLength = policy.codeLength,
                expiresInSeconds = policy.ttl.inWholeSeconds,
                resendAfterSeconds = policy.resendAfter.inWholeSeconds,
            )
        }
}
