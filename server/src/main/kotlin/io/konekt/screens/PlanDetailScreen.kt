package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.purchase.shared.api.BuyPlanAction
import io.konekt.feature.roaming.server.domain.Zones
import io.konekt.money.MoneyFormat
import io.konekt.roaming.RoamingZoneNames

// THE SCREEN THAT WAS MISSING BETWEEN THE CATALOGUE AND THE MONEY.
//
// Pressing a plan card used to create an order. The canvas draws a detail screen in between and it
// is not decoration: it is the only place a subscriber sees what they are about to buy before
// anything is spent, and its absence made a catalogue of cards into a page of buttons that charge.
//
// WHAT IT DOES NOT DRAW is as deliberate as what it does. The canvas's frame lists the network
// operators, "5G where available", "Hotspot allowed", a top-up price and a sentence about the device
// having been checked. None of those is anything this product knows — there is no operator list, no
// radio generation and no device check in the domain — and a row that states one would be a mockup
// wearing a product's clothes. What is here is what the catalogue actually carries.
object PlanDetailScreen {
    fun build(
        plan: Plan,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "plan-detail",
            spacing = 12,
            children =
                buildList {
                    add(
                        TextComponent(
                            id = "plan-detail-title",
                            text = plan.title,
                            style = M3Typography.HeadlineSmall,
                            color = M3Colors.OnSurface,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "plan-detail-price",
                            text = MoneyFormat.format(plan.price),
                            style = M3Typography.DisplaySmall,
                            color = M3Colors.OnSurface,
                        ),
                    )

                    addAll(includes(plan))

                    // WHEN IT STARTS COUNTING, and this is the fact worth a whole line. A roaming
                    // package is provisioned DORMANT — bought at home, counting nothing until the
                    // trip — and a subscriber who does not know that is a subscriber who thinks they
                    // are being charged for a holiday that has not happened.
                    add(
                        BannerComponent(
                            id = "plan-detail-activation",
                            text =
                                if (plan.zone == Zones.HOME) {
                                    "Added to your line as soon as the purchase completes."
                                } else {
                                    "Starts counting when you first connect in " +
                                        "${RoamingZoneNames.of(plan.zone)}, not when you buy it."
                                },
                            tone = MessageTones.INFO,
                        ),
                    )

                    add(
                        if (plan.onSale) {
                            ButtonComponent(
                                id = "plan-detail-buy",
                                // The price in the label, as the canvas has it. A button that says
                                // only "Buy" asks somebody to remember a number from further up the
                                // screen at the moment they commit to it.
                                text = "Buy for ${MoneyFormat.format(plan.price)}",
                                action = BuyPlanAction(plan.id),
                                modifiers = FILLS_THE_ROW,
                            )
                        } else {
                            // NO ACTION AT ALL rather than a disabled-looking button with one. The
                            // catalogue already marks it sold out; here the absence is the answer,
                            // and the server refuses the purchase besides.
                            BannerComponent(
                                id = "plan-detail-sold-out",
                                text = "This plan is not on sale right now.",
                                tone = MessageTones.ERROR,
                            )
                        },
                    )

                    nav?.let(::add)
                },
        )

    // What the plan is made of, one row per thing it includes. Rows rather than a sentence because
    // the canvas draws a table and because a list is what a person compares two plans with.
    private fun includes(plan: Plan): List<KompotComponent> =
        buildList {
            add(
                TextComponent(
                    id = "plan-detail-includes",
                    text = "What is included",
                    style = M3Typography.LabelMedium,
                    color = M3Colors.OnSurfaceVariant,
                ),
            )
            if (plan.dataMb > 0) {
                add(
                    row(
                        "data",
                        "Data",
                        io.konekt.feature.usage.server.data.UsageUnits
                            .megabytes(plan.dataMb),
                    ),
                )
            }
            // STATED AS "NOT INCLUDED" rather than left out, and the canvas is explicit about this:
            // its detail frame carries a "Calls & SMS — not included" row. An absent row answers
            // nothing; a subscriber comparing a home bundle with a travel package is asking exactly
            // this question.
            add(row("minutes", "Calls", if (plan.minutes > 0) "${plan.minutes} minutes" else "Not included"))
            add(row("messages", "SMS", if (plan.messages > 0) "${plan.messages} messages" else "Not included"))
            if (plan.validForDays > 0) {
                add(row("validity", "Runs for", "${plan.validForDays} days once it starts"))
            }
            if (plan.zone != Zones.HOME) {
                add(row("zone", "Works in", RoamingZoneNames.of(plan.zone)))
            }
        }

    private fun row(
        id: String,
        label: String,
        value: String,
    ): KompotComponent =
        RowComponent(
            id = "plan-detail-$id",
            spacing = 8,
            children =
                listOf(
                    TextComponent(
                        id = "plan-detail-$id-label",
                        text = label,
                        style = M3Typography.BodyMedium,
                        color = M3Colors.OnSurfaceVariant,
                    ),
                    TextComponent(
                        id = "plan-detail-$id-value",
                        text = value,
                        style = M3Typography.BodyMedium,
                        color = M3Colors.OnSurface,
                    ),
                ),
        )
}
