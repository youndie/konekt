package io.konekt.feature.auth.server.domain

import io.konekt.domain.Money
import kotlin.time.Instant

// What this feature needs from the world, as interfaces it owns.
//
// They live here rather than in -server-data because the dependency points inwards: the domain
// declares what it needs, and the data layer implements it. That is also what lets these use cases be
// tested against MockK rather than against a database.

interface OtpRepository {
    suspend fun find(msisdn: Msisdn): OtpChallenge?

    // Replaces whatever was there. One live code per number.
    suspend fun put(challenge: OtpChallenge)

    // Returns the challenge as it now stands, so the caller can see whether this attempt was the one
    // that locked it. Doing the read and the write in one call is not tidiness — two calls race, and
    // the thing they race over is a guessing budget.
    suspend fun recordFailedAttempt(
        msisdn: Msisdn,
        lockUntil: Instant?,
    ): OtpChallenge?

    suspend fun clear(msisdn: Msisdn)
}

data class Subscriber(
    val id: String,
    val msisdn: Msisdn,
)

interface SubscriberRepository {
    suspend fun findByMsisdn(msisdn: Msisdn): Subscriber?

    // Creating the account alongside is deliberate and atomic: a subscriber without an account is a
    // row every balance read has to defend against, forever, because of one interrupted sign-up.
    suspend fun createWithAccount(
        msisdn: Msisdn,
        openingBalance: Money,
    ): Subscriber
}

// Random digits, from a source fit for the purpose. An interface because the test needs a code it
// knows, and because "which random" is exactly the decision that should be visible.
fun interface CodeGenerator {
    fun generate(length: Int): String
}

// Turns a code into what is stored. See the implementation for why this is keyed rather than a plain
// digest.
fun interface CodeHasher {
    fun hash(code: String): String
}

data class Session(
    val accessToken: String,
    val refreshToken: String,
)

fun interface SessionIssuer {
    fun issue(subscriber: Subscriber): Session
}

// Where the code goes instead of to a phone. The boundary of this system stops at the SMSC.
fun interface OtpDelivery {
    fun deliver(
        msisdn: Msisdn,
        code: String,
    )
}
