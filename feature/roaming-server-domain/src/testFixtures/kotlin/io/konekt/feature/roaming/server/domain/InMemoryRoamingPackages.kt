package io.konekt.feature.roaming.server.domain

import kotlin.time.Instant

// The repository without a database, for tests that are about something else — a saga's compensation,
// a chain's routing — and only need the roaming side to behave.
//
// ONE COPY IN TEST FIXTURES rather than an anonymous object per test file. Three hand-rolled fakes are
// three chances for one of them to grant a package that is already started, which would make the
// saga test using it pass on code that provisions roaming wrongly.
//
// It deliberately mirrors `ExposedRoamingPackages`, including the clamp and the
// already-started-first ordering, so a test cannot pass here and fail there.
class InMemoryRoamingPackages(
    private val now: () -> Instant,
) : RoamingPackages {
    private val packages = mutableListOf<RoamingPackage>()

    override suspend fun grant(
        orderId: String,
        subscriberId: String,
        zone: String,
        limitMb: Long,
        validForDays: Long,
        purchasedAt: Instant,
    ) {
        // The unique constraint on order_id, in the only form this class can have one.
        if (packages.any { it.orderId == orderId }) return
        packages +=
            RoamingPackage(
                id = "pkg-${packages.size + 1}",
                orderId = orderId,
                subscriberId = subscriberId,
                zone = zone,
                limitMb = limitMb,
                remainingMb = limitMb,
                validForDays = validForDays,
                purchasedAt = purchasedAt,
                activatedAt = null,
                expiresAt = null,
            )
    }

    override suspend fun revoke(orderId: String) {
        packages.removeAll { it.orderId == orderId && it.dormant }
    }

    override suspend fun of(subscriberId: String): List<RoamingPackage> =
        packages.filter { it.subscriberId == subscriberId }.sortedBy { it.purchasedAt }

    override suspend fun travelling(): List<Travelling> =
        packages
            .filter { !it.dormant && it.usableAt(now()) }
            .map { Travelling(it.subscriberId, it.zone) }
            .distinct()

    override suspend fun consume(
        subscriberId: String,
        zone: String,
        megabytes: Long,
        at: Instant,
    ): RoamingConsumption {
        val candidates =
            packages.filter { it.subscriberId == subscriberId && it.zone == zone && it.usableAt(at) }
        val pkg =
            candidates.firstOrNull { !it.dormant }
                ?: candidates.minByOrNull { it.purchasedAt }
                ?: return RoamingConsumption.NoPackage

        val starting = pkg.dormant
        val taken = minOf(megabytes, pkg.remainingMb)
        val updated =
            pkg.copy(
                remainingMb = pkg.remainingMb - taken,
                activatedAt = if (starting) at else pkg.activatedAt,
                expiresAt = if (starting) pkg.expiryIfActivatedAt(at) else pkg.expiresAt,
            )
        packages[packages.indexOf(pkg)] = updated
        return RoamingConsumption.Counted(updated, taken, starting)
    }
}
