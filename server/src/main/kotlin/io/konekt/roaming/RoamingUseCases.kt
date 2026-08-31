package io.konekt.roaming

import io.konekt.domain.suspendRunCatching
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.purchase.server.domain.PlanCatalog
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.roaming.server.domain.Zones
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
    // WHAT CAN BE BOUGHT, on the screen named after it. Without this the travel screen answered the
    // question "what have I got" and had no answer at all to "how do I get one" — so a subscriber
    // who arrived with nothing found one banner and concluded there was nothing to buy (`B-103`).
    //
    // The plans themselves and not a summary: the card is built by the catalogue's own builder, so
    // the price, the badge and the sold-out state are decided in one place for both screens.
    val onOffer: List<Plan> = emptyList(),
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
    private val catalogue: PlanCatalog,
    private val clock: KonektClock,
) {
    suspend operator fun invoke(subscriberId: String): Result<RoamingView> =
        suspendRunCatching {
            val now = clock.now()
            val mine = packages.of(subscriberId)

            RoamingView(
                at = now,
                // EVERY TRAVEL PLAN THE CATALOGUE HAS, sold out ones included. A catalogue that
                // silently omits what it will not sell teaches a subscriber that the list is what
                // exists — and the refusal path needs its fixture to be findable.
                //
                // The zone is what makes a plan a travel plan; `Zones.HOME` is the absence of
                // roaming rather than somewhere anyone goes.
                onOffer = catalogue.all().filter { it.zone != Zones.HOME },
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
