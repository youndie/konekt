package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.components.PlanCardComponent
import io.konekt.components.PlanStates
import io.konekt.feature.packages.shared.api.CUSTOM_PACKAGE_DEEPLINK
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
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

                    // THE WAY INTO THE BUILDER, and its absence is `B-87`: the form was in no graph
                    // and nothing in the application led to it, so a subscriber could not have found
                    // it however well it priced.
                    //
                    // AFTER the catalogue rather than before it. What is on sale is what most people
                    // want; building your own is the answer for the ones the list does not fit, and a
                    // page that opens with it puts a configuration exercise in front of a purchase.
                    add(
                        BannerComponent(
                            id = "plans-custom",
                            text =
                                "None of these quite right? Build a package with the data, " +
                                    "minutes and messages you want.",
                            tone = MessageTones.INFO,
                            action = NavigateAction(CUSTOM_PACKAGE_DEEPLINK),
                            actionText = "Build your own",
                        ),
                    )

                    // THE SHELL, added last and hoisted by the client out of the tree it arrived in.
                    // In the tree rather than fetched separately so the SERVER decides which tab is
                    // current: it is the only side that knows which screen it just built.
                    nav?.let(::add)
                },
        )

    private fun card(plan: Plan): PlanCardComponent =
        PlanCardComponent(
            id = "plan-${plan.id}",
            // THE PLACE, AND THE QUOTA BENEATH IT — which is how the canvas draws a card and what
            // this line used to prevent. `plan.title` is "Turkey · 10 GB · 30 days", so the card said
            // the quota twice: once glued into a heading and again in `quotaTexts` under it. The
            // component could always express the canvas's layout; the server was not using it.
            //
            // `plan.title` keeps its full form because the HISTORY needs it: a row from three months
            // ago has no card under it to carry the rest, and "Turkey" alone would not say which
            // Turkey plan was bought.
            title = RoamingZoneNames.of(plan.zone),
            priceText = MoneyFormat.format(plan.price),
            // WHAT A GIGABYTE COSTS, which is the comparison the canvas puts on every card and the
            // one a column of totals actively prevents: $9 for 5 GB is dearer than $15 for 20 GB, and
            // nothing on the old card said so. `null` for a plan with no data to divide by.
            //
            // THE PRICE IS SCALED, NOT THE QUOTA, and the first version of this line got it backwards:
            // it divided by `dataMb` and labelled the answer "GB", which is a price per MEGABYTE with
            // the wrong unit under it — off by a factor of 1024 in the direction that makes every plan
            // look free. `price × 1024 ÷ dataMb` keeps the arithmetic in whole minor units, and the
            // 1024 is the same base `UsageUnits` uses for the "20 GB" beside it: two figures on one
            // card computed in two bases would disagree with each other for a living.
            perUnitText = MoneyFormat.perUnit(plan.price * MB_PER_GB, plan.dataMb, "GB"),
            quotaTexts = quotas(plan),
            badgeText = if (plan.onSale) "On sale" else null,
            // SOLD OUT IS A STATE AND NOT AN ABSENCE. `us-20gb-30d` carries `onSale = false` and is
            // deliberately in the catalogue: the refusal path needs a fixture, and a subscriber who
            // was told about a plan should find it rather than find nothing.
            state = if (plan.onSale) PlanStates.AVAILABLE else PlanStates.SOLD_OUT,
            // BUYING, and only for what is on sale. A card that accepts a press and then refuses is
            // worse than one that does not accept it — the renderer refuses the press too, and both
            // halves are needed: the client decides what is pressable and the server decides what is
            // sold, and neither may be the only one that knows.
            // NAVIGATES NOW, AND DOES NOT BUY. Pressing a card used to create an order — the
            // catalogue was a page of buttons that charge you — and the canvas draws a detail screen
            // in between, which is where the money is agreed to.
            //
            // The deeplink needs no new entry in the client's route map: `app://plans` is already
            // there and the resolver carries everything after a matched prefix across unchanged.
            action = if (plan.onSale) NavigateAction("$PLANS_DEEPLINK/${plan.id}") else null,
        )

    // What the plan is made of, said in the units a person uses. Built from `dataMb` rather than
    // parsed out of the title: "Turkey · 10 GB · 30 days" is copy, and parsing copy for a number is
    // how a renamed plan silently advertises nothing.
    // EVERYTHING THE PLAN INCLUDES, and minutes and messages used to be missing from it.
    //
    // The home bundle carries 300 minutes and 50 messages — the canvas's own numbers — and the card
    // listed "20 GB" and stopped. A subscriber comparing it against a roaming package was comparing
    // gigabytes against gigabytes while one of the two also included calls, which is the comparison
    // the card exists to make and the one it was getting wrong by omission.
    // The same base `UsageUnits` writes "20 GB" with. Spelled here rather than imported because the
    // usage feature's copy is private to it — and named rather than written as 1024 at the call site,
    // so the two cannot drift apart silently.
    private const val MB_PER_GB = 1024L

    private fun quotas(plan: Plan): List<String> =
        buildList {
            if (plan.dataMb > 0) {
                add(
                    io.konekt.feature.usage.server.data.UsageUnits
                        .megabytes(plan.dataMb),
                )
            }
            if (plan.minutes > 0) add("${plan.minutes} min")
            if (plan.messages > 0) add("${plan.messages} SMS")
            if (plan.validForDays > 0) add("${plan.validForDays} days once it starts")
        }
}
