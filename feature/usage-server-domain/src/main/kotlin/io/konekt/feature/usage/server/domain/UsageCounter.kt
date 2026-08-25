package io.konekt.feature.usage.server.domain

import io.konekt.domain.suspendRunCatching

// What a subscriber has left, and of what.
//
// A COUNTER IS NOT MONEY, and that is why it is a plain Long rather than the Money type: minor units
// and a currency have no meaning for megabytes, and the exponent that makes Money worth having is
// exactly what a byte count does not need. The two look alike and behave differently, which is the
// argument for two types rather than one clever one.
data class UsageCounter(
    val subscriberId: String,
    val kind: Kind,
    val limitUnits: Long,
    val remainingUnits: Long,
) {
    enum class Kind(
        val wireName: String,
        // Singular, for the label. Plural is the label's business and belongs on the server side of
        // the screen, not here.
        val unit: String,
    ) {
        DATA("data", "MB"),
        MINUTES("minutes", "min"),
        MESSAGES("messages", "SMS"),
    }

    val usedUnits: Long get() = limitUnits - remainingUnits

    // 0..1, and null when there is no ceiling. A bar cannot be drawn for an unlimited allowance, and
    // drawing a full one would say the opposite of what is true.
    val progress: Float? get() = if (limitUnits <= 0) null else (usedUnits.toDouble() / limitUnits).toFloat()

    val isExhausted: Boolean get() = remainingUnits <= 0

    // Below a tenth, which is a judgement the SERVER makes because it is the side that knows the
    // subscriber's rate of use. A client deciding "low" from the number alone would have to guess.
    val isLow: Boolean get() = !isExhausted && limitUnits > 0 && remainingUnits * 10 <= limitUnits
}

interface UsageCounters {
    suspend fun of(subscriberId: String): List<UsageCounter>

    suspend fun find(
        subscriberId: String,
        kind: UsageCounter.Kind,
    ): UsageCounter?

    // Adds an allowance, creating the counter if this is the first. A purchase grants; nothing else
    // does.
    suspend fun grant(
        subscriberId: String,
        kind: UsageCounter.Kind,
        units: Long,
    )

    // Returns the counter as it now stands, so the caller can push the new value without a second
    // read — and so the decrement and the read cannot disagree under two consumers.
    //
    // NEVER BELOW ZERO. A counter that goes negative is a screen that says minus four hundred
    // megabytes, and the clamp lives in the database because two decrements arriving together both
    // pass a read-then-check.
    suspend fun consume(
        subscriberId: String,
        kind: UsageCounter.Kind,
        units: Long,
    ): UsageCounter?
}

// What a purchase hands over. In the USAGE domain rather than the purchase one because the shape of
// an allowance is this feature's business — the purchase only knows it bought a plan.
interface UsageGrants {
    suspend fun grantPlanAllowance(
        subscriberId: String,
        dataMb: Long,
    )
}

class ConsumeUsageUseCase(
    private val counters: UsageCounters,
) {
    suspend operator fun invoke(params: Params): Result<UsageCounter?> =
        suspendRunCatching {
            counters.consume(params.subscriberId, params.kind, params.units)
        }

    data class Params(
        val subscriberId: String,
        val kind: UsageCounter.Kind,
        val units: Long,
    )
}

class LoadCountersUseCase(
    private val counters: UsageCounters,
) {
    suspend operator fun invoke(subscriberId: String): Result<List<UsageCounter>> =
        suspendRunCatching { counters.of(subscriberId) }
}
