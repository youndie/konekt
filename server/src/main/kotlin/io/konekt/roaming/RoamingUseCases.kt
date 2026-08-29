package io.konekt.roaming

import io.konekt.domain.suspendRunCatching
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.time.KonektClock
import kotlin.time.Instant

// WHAT THIS LINE HAS FOR ITS TRIPS, grouped and ordered before anything draws — `B-96` on the travel
// screen.
//
// AND ONE `now` FOR THE WHOLE ANSWER, which is the reason this is worth more here than it was on the
// profile. `RoamingScreen` read the clock to rank the zones and `RoamingPackageCards` read it again
// for every caption, so a response built across a tick could rank a package as running and then
// caption it as ended. The repository already refuses to work that way — `travelling()` filters every
// row against a single instant and says so — and the screen did not. Reading it once, here, is what
// makes that impossible rather than unlikely.
data class RoamingView(
    val at: Instant,
    val zones: List<RoamingZoneView>,
)

// ONE ZONE, WITH ITS NAME ALREADY RESOLVED and its packages in the order they will be spent.
//
// The ORDER of the list is the decision, and it is the screen's whole subject: trips under way first,
// then the ones waiting, then what has ended. That is the order of a subscriber's attention, and it
// is a fact about time rather than about layout — which is why it is decided here and not in a
// comparator inside a renderer.
data class RoamingZoneView(
    val zone: String,
    val title: String,
    val packages: List<RoamingPackage>,
)

class ViewRoamingUseCase(
    private val packages: RoamingPackages,
    private val clock: KonektClock,
) {
    suspend operator fun invoke(subscriberId: String): Result<RoamingView> =
        suspendRunCatching {
            val now = clock.now()
            val mine = packages.of(subscriberId)

            RoamingView(
                at = now,
                zones =
                    mine
                        .groupBy { it.zone }
                        .toList()
                        .sortedBy { (_, inZone) -> rankOf(inZone, now) }
                        .map { (zone, inZone) ->
                            // Within a zone, the order they will be SPENT in — which is the order
                            // `RoamingPackages.of` already returns and the order `consume` actually
                            // uses. Sorting them any other way would make the screen disagree with
                            // the meter.
                            RoamingZoneView(zone, RoamingZoneNames.of(zone), inZone)
                        },
            )
        }

    // A zone is as urgent as its most urgent package. Named as a function rather than inlined in the
    // comparator, because "which of these three states is this zone in" is the whole ordering and a
    // reader should be able to find it.
    private fun rankOf(
        inZone: List<RoamingPackage>,
        now: Instant,
    ): Int =
        inZone.minOf { pkg ->
            when {
                !pkg.dormant && !pkg.expiredAt(now) -> RUNNING
                pkg.dormant -> WAITING
                else -> ENDED
            }
        }

    private companion object {
        const val RUNNING = 0
        const val WAITING = 1
        const val ENDED = 2
    }
}
