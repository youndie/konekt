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
//
// THE GROUPING AND THE ORDER MOVED OUT, to `ViewRoamingUseCase` (`B-96`). They are answers about
// time, and this class had to hold a `KonektClock` to work them out — a renderer that can ask the
// time can disagree with the answer it was given, and this one did: it ranked the zones against one
// `now` while the cards captioned themselves against another.
class RoamingScreen(
    private val cards: RoamingPackageCards,
) {
    fun build(
        view: RoamingView,
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

                    if (view.zones.isEmpty()) {
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
                        view.zones.forEach { zone ->
                            add(
                                TextComponent(
                                    id = "roaming-zone-${zone.zone}",
                                    text = zone.title,
                                    style = M3Typography.LabelMedium,
                                    color = M3Colors.OnSurfaceVariant,
                                ),
                            )
                            addAll(zone.packages.map(cards::of))
                        }
                    }

                    nav?.let(::add)
                },
        )
}
