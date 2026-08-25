package io.konekt.feature.auth.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class RequestOtpRequest(
    // As the subscriber typed it. Normalisation is the server's job and happens in one place, because
    // a number stored two ways is a subscriber who can sign in twice and own two balances.
    val msisdn: String,
)

// Deliberately says nothing about whether the number is known. Requesting a code for a number that
// has never been seen returns exactly this, with the same values and the same work behind it — the
// alternative is an endpoint that tells anyone who asks which numbers are subscribers.
@Serializable
data class RequestOtpResponse(
    val codeLength: Int,
    val expiresInSeconds: Long,
    // What the screen counts down before it re-enables "send again". A client told to wait with no
    // number picks one, and the number it picks is "immediately".
    val resendAfterSeconds: Long,
)

@Serializable
data class VerifyOtpRequest(
    val msisdn: String,
    val code: String,
)

// The development-only view of the code the SMSC would have carried.
@Serializable
data class DevOtpResponse(
    val msisdn: String,
    val code: String,
    val expiresAtEpochMs: Long,
)

@Serializable
data class RefreshSessionRequest(
    val refreshToken: String,
)
