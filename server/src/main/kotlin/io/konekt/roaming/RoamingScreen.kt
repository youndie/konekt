package io.konekt.roaming

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.time.KonektClock

// WHAT YOU HAVE FOR THIS TRIP, which is the question `B-19` built the whole vertical for and gave
// nobody a place to ask.
//
// Packages appeared as cards on the home screen, mixed in among the home allowance, in the order they
// were bought. That answers "how much is left" and not "what do I have for Turkey" — and the second
// question is the one somebody asks at an airport.
//
// GROUPED BY ZONE, and the grouping IS the screen. Everything else here — the cards, the dormant
// state, the dates — already existed; what did not exist was a page that puts two Turkey packages
// next to each other and a Europe one under its own heading.
class RoamingScreen(
    private val cards: RoamingPackageCards,
    private val clock: KonektClock,
) {
    fun build(
        packages: List<RoamingPackage>,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "roaming",
            spacing = 12,
            children =
                buildList {
                    add(
                        TextComponent(
                            id = "roaming-title",
                            text = "Travel packages",
                            style = M3Typography.HeadlineSmall,
                            color = M3Colors.OnSurface,
                        ),
                    )

                    if (packages.isEmpty()) {
                        // A screen that draws nothing is indistinguishable from one that failed to
                        // load — the same rule the home screen and the catalogue follow — and this
                        // one can say something useful besides: where the packages are sold.
                        add(
                            BannerComponent(
                                id = "roaming-empty",
                                text = "No travel package on this line yet.",
                                tone = MessageTones.INFO,
                                action = NavigateAction(PLANS_DEEPLINK),
                                actionText = "See plans",
                            ),
                        )
                    } else {
                        addAll(zones(packages))
                    }

                    nav?.let(::add)
                },
        )

    // ONE HEADING PER ZONE, and the ORDER is a rule rather than a map's iteration.
    //
    // Trips under way first, then the ones waiting, then what has ended. That is the order of a
    // subscriber's attention: what is counting right now matters most, and an ended package is
    // history they are only checking.
    private fun zones(packages: List<RoamingPackage>): List<KompotComponent> {
        val now = clock.now()
        return packages
            .groupBy { it.zone }
            .toList()
            .sortedBy { (_, inZone) -> rankOf(inZone, now) }
            .flatMap { (zone, inZone) ->
                buildList {
                    add(
                        TextComponent(
                            id = "roaming-zone-$zone",
                            text = RoamingZoneNames.of(zone),
                            style = M3Typography.LabelMedium,
                            color = M3Colors.OnSurfaceVariant,
                        ),
                    )
                    // Within a zone, the order they will be SPENT in — which is the order
                    // `RoamingPackages.of` already returns and the order `consume` actually uses.
                    // Sorting them any other way here would make the screen disagree with the meter.
                    addAll(inZone.map(cards::of))
                }
            }
    }

    // A zone is as urgent as its most urgent package. Named as a function rather than inlined in the
    // comparator, because "which of these three states is this zone in" is the whole ordering and a
    // reader should be able to find it.
    private fun rankOf(
        inZone: List<RoamingPackage>,
        now: kotlin.time.Instant,
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
