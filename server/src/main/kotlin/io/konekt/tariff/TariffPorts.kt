package io.konekt.tariff

import kotlin.time.Instant

// The catalogue. In memory, because the BSS is outside this system's boundary — the same reason
// `StaticPlanCatalog` is, and the same shape.
interface TariffCatalogue {
    fun find(tariffId: String): Tariff?

    fun all(): List<Tariff>

    // What a subscriber is on when they have never changed. A property of the catalogue rather than a
    // column with a default, so a deployment that renames its base tariff does not need a migration.
    val default: Tariff
}

// The log of changes. Append-only: "since when" and "what before" come free, and a change awaiting
// confirmation has somewhere to sit that is not also the current answer.
interface TariffChanges {
    suspend fun currentTariffId(subscriberId: String): String?

    suspend fun pendingOf(subscriberId: String): TariffChangeRecord?

    suspend fun record(
        changeId: String,
        subscriberId: String,
        fromTariffId: String,
        toTariffId: String,
        effectiveAt: Instant,
    )

    suspend fun apply(changeId: String)

    suspend fun cancel(changeId: String)

    suspend fun findByChange(changeId: String): TariffChangeRecord?
}

data class TariffChangeRecord(
    val changeId: String,
    val subscriberId: String,
    val fromTariffId: String,
    val toTariffId: String,
    val status: String,
    val effectiveAt: Instant,
)

// The three states a change can be in, named where both the repository and a test can see them. The
// table declares the same three; this is what keeps a test from spelling one of them itself.
object TariffChangeStatuses {
    const val PENDING = "pending"
    const val APPLIED = "applied"
    const val CANCELLED = "cancelled"
}
