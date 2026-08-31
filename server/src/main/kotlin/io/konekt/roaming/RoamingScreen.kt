package io.konekt.roaming

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.screens.PlansScreen

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
                        // load — the same rule the home screen and the catalogue follow.
                        //
                        // AND IT NO LONGER SENDS ANYBODY ELSEWHERE. The banner used to carry
                        // "See plans", because the offer was on another screen; it is below this line
                        // now, so a control pointing away from it would be a door out of the room a
                        // subscriber has just been let into (`B-103`).
                        add(
                            BannerComponent(
                                id = "roaming-empty",
                                text = "No travel package on this line yet.",
                                tone = MessageTones.INFO,
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
                            addAll(zone.packages.map { cards.of(it, view.at) })
                        }
                    }

                    // WHAT CAN BE BOUGHT, under what is already held. The order is the order of the
                    // questions: what have I got, then what else is there — the same order the home
                    // screen puts its counters and its catalogue door in.
                    if (view.onOffer.isNotEmpty()) {
                        add(
                            TextComponent(
                                id = "roaming-offer-title",
                                text = "Packages for your next trip",
                                style = M3Typography.LabelMedium,
                                color = M3Colors.OnSurfaceVariant,
                            ),
                        )
                        // THE CATALOGUE'S OWN CARD BUILDER. Not a card of this screen's making: one
                        // plan with two builders is one plan with two prices the first time either is
                        // edited.
                        addAll(view.onOffer.map(PlansScreen::card))
                    }

                    nav?.let(::add)
                },
        )
}
