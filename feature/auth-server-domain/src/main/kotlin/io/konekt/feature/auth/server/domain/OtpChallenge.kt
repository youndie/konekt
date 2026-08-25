package io.konekt.feature.auth.server.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// One outstanding code per number. A new request replaces the old one rather than adding to it: two
// live codes for one number doubles the guessing surface for no benefit anybody asked for.
data class OtpChallenge(
    val msisdn: Msisdn,
    // The code is never stored. See CodeHasher for why the hash alone is close to worthless and what
    // makes it worth having anyway.
    val codeHash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val attemptsUsed: Int,
    // Set once the attempts run out. Separate from expiresAt because they mean different things: a
    // code expires and can be replaced, a number is locked and cannot.
    val lockedUntil: Instant?,
) {
    fun isExpired(now: Instant): Boolean = now >= expiresAt

    fun isLocked(now: Instant): Boolean = lockedUntil?.let { now < it } == true
}

// The numbers that decide how this feature behaves, in one place so they can be read together and
// argued about as a set.
data class OtpPolicy(
    // Six digits is a million codes against a six-attempt budget, so a guesser gets one chance in
    // about a hundred and seventy thousand per lockout window. Longer is not free: a code a
    // subscriber cannot hold in their head is a code they mistype.
    val codeLength: Int = 6,
    val ttl: Duration = 5.minutes,
    // Attempts across the life of one code, not per session. The lockout is what turns a guessing
    // budget into a rate.
    val maxAttempts: Int = 6,
    val lockout: Duration = 15.minutes,
    // How long before "send again" does anything. Also the reason a resend cannot be used to reset
    // the attempt counter — see RequestOtpUseCase.
    val resendAfter: Duration = 60.seconds,
)
