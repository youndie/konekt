package io.konekt.feature.usage.server.domain

import io.konekt.domain.Money
import io.konekt.domain.suspendRunCatching
import kotlin.time.Instant

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
    // When the allowance started. Carried because the projection below is the only thing that can be
    // said about a rate of use without a table of every decrement — and the canvas asks for exactly
    // that sentence.
    val startedAt: Instant,
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

    // How long what is left will last at the pace so far, in days. Null when the question cannot be
    // answered rather than zero when it cannot: nothing used yet, or an allowance that started this
    // instant, is not "runs out today".
    //
    // A MEAN AND NOT A TREND, and the difference is worth stating because the sentence it produces
    // sounds more certain than it is. The rate is everything used divided by the whole time the
    // allowance has existed, so a subscriber who watched a film on the first day and nothing since
    // is told they have two days left when they have a fortnight. A trend needs a history of
    // decrements, which is a table this product does not have and would not read twice — the screen
    // shows what is left, and the ledger already records what was paid for. The word "about" in the
    // copy is doing real work.
    fun daysRemaining(now: Instant): Double? {
        if (isExhausted || usedUnits <= 0) return null

        val elapsedDays = (now - startedAt).inWholeMinutes / MINUTES_PER_DAY
        if (elapsedDays <= 0.0) return null

        val perDay = usedUnits / elapsedDays
        if (perDay <= 0.0) return null

        return remainingUnits / perDay
    }

    private companion object {
        const val MINUTES_PER_DAY = 24.0 * 60.0
    }
}

// What a subscriber can buy to top one counter up. The BSS is outside the boundary, so this is a
// price list rather than a catalogue — and it is the usage feature's rather than the purchase
// feature's, because what an allowance is made of is this domain's business.
data class UsageAddOn(
    val units: Long,
    val price: Money,
)

interface UsageAddOns {
    fun forKind(kind: UsageCounter.Kind): UsageAddOn?
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

    // The other half, and it exists because the granting step sits inside a saga. A purchase that is
    // rolled back after the allowance landed would otherwise leave the subscriber holding data they
    // did not pay for — the money returns and the gigabytes stay, which is the asymmetry a
    // compensation exists to prevent.
    //
    // Clamped at zero like every other decrement here: some of it may already have been spent, and a
    // negative allowance is a screen that says minus four hundred megabytes.
    suspend fun revokePlanAllowance(
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
