package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.components.PlanCardComponent
import io.konekt.components.PlanStates
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.purchase.shared.api.BuyPlanAction
import io.konekt.money.MoneyFormat
import io.konekt.roaming.RoamingZoneNames

// THE DESTINATION `app://plans` HAS POINTED AT SINCE B-07, and until now there was nothing there.
//
// The home screen's "no plan is active" banner offers "See plans" and the screen document said so
// outright — *"a destination that does not exist in this build"*. It is the second screen this client
// can reach, and the first one reached by a subscriber pressing something.
//
// EVERY STRING IS COMPOSED HERE, including the price and the quota lines. The client renders
// `priceText` and `quotaTexts` as they came and owns no formatter for either (D15) — which is also
// what makes a price change a server deploy rather than a release.
object PlansScreen {
    fun build(
        plans: List<Plan>,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "plans",
            spacing = 12,
            children =
                buildList {
                    add(
                        TextComponent(
                            id = "plans-title",
                            text = "Plans",
                            style = M3Typography.HeadlineSmall,
                            color = M3Colors.OnSurface,
                        ),
                    )

                    if (plans.isEmpty()) {
                        // The same rule the home screen follows: a screen that draws nothing is
                        // indistinguishable from one that failed to load.
                        add(
                            BannerComponent(
                                id = "plans-empty",
                                text = "There is nothing on sale right now.",
                                tone = MessageTones.INFO,
                            ),
                        )
                    } else {
                        addAll(plans.map(::card))
                    }

                    // THE SHELL, added last and hoisted by the client out of the tree it arrived in.
                    // In the tree rather than fetched separately so the SERVER decides which tab is
                    // current: it is the only side that knows which screen it just built.
                    nav?.let(::add)
                },
        )

    private fun card(plan: Plan): PlanCardComponent =
        PlanCardComponent(
            id = "plan-${plan.id}",
            title = plan.title,
            priceText = MoneyFormat.format(plan.price),
            quotaTexts = quotas(plan),
            // The zone as a person reads it, and absent for a home bundle: "Home" on a plan nobody
            // travels with is a line that says nothing and takes a line's worth of attention.
            zoneText =
                if (plan.zone == io.konekt.feature.roaming.server.domain.Zones.HOME) {
                    null
                } else {
                    "Works in ${RoamingZoneNames.of(plan.zone)}"
                },
            badgeText = if (plan.onSale) "On sale" else null,
            // SOLD OUT IS A STATE AND NOT AN ABSENCE. `us-20gb-30d` carries `onSale = false` and is
            // deliberately in the catalogue: the refusal path needs a fixture, and a subscriber who
            // was told about a plan should find it rather than find nothing.
            state = if (plan.onSale) PlanStates.AVAILABLE else PlanStates.SOLD_OUT,
            // BUYING, and only for what is on sale. A card that accepts a press and then refuses is
            // worse than one that does not accept it — the renderer refuses the press too, and both
            // halves are needed: the client decides what is pressable and the server decides what is
            // sold, and neither may be the only one that knows.
            action = if (plan.onSale) BuyPlanAction(plan.id) else null,
        )

    // What the plan is made of, said in the units a person uses. Built from `dataMb` rather than
    // parsed out of the title: "Turkey · 10 GB · 30 days" is copy, and parsing copy for a number is
    // how a renamed plan silently advertises nothing.
    private fun quotas(plan: Plan): List<String> =
        buildList {
            if (plan.dataMb > 0) {
                add(
                    io.konekt.feature.usage.server.data.UsageUnits
                        .megabytes(plan.dataMb),
                )
            }
            if (plan.validForDays > 0) add("${plan.validForDays} days once it starts")
        }
}
