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
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceDensities
import io.konekt.components.SurfaceTones
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.purchase.shared.api.BuyPlanAction
import io.konekt.feature.roaming.server.domain.Zones
import io.konekt.feature.usage.server.data.UsageUnits
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
    // THE CANVAS'S PLAN DETAIL (`B-114`, block 2): a mint hero with the quota as the figure, the
    // attributes as chips, a white table of what is included, the activation note, and the buy
    // control PINNED to the bottom with `Charged once` over it. It was a title, a price and a list.
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
                    add(hero(plan))
                    add(includes(plan))
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
                            footer(plan)
                        } else {
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

    // THE HERO. The quota is the figure — `headline_medium`, the grotesk — because a plan IS its
    // allowance and the price is what it costs, in that order of importance; the canvas draws the
    // price top-right in the title face and the quota at 44 underneath the label.
    private fun hero(plan: Plan): KompotComponent =
        SurfaceComponent(
            id = "plan-detail-hero",
            tone = SurfaceTones.ACCENT,
            spacing = 12,
            children =
                buildList {
                    add(
                        RowComponent(
                            id = "plan-detail-hero-head",
                            spacing = 12,
                            children =
                                listOf(
                                    TextComponent(
                                        id = "plan-detail-hero-label",
                                        text = if (plan.zone == Zones.HOME) "Plan" else "Data plan",
                                        style = M3Typography.LabelMedium,
                                        color = M3Colors.OnPrimaryContainer,
                                        modifiers = TAKES_THE_SPACE,
                                    ),
                                    TextComponent(
                                        id = "plan-detail-price",
                                        text = MoneyFormat.format(plan.price),
                                        style = M3Typography.TitleMedium,
                                        color = M3Colors.OnPrimaryContainer,
                                    ),
                                ),
                        ),
                    )
                    add(
                        TextComponent(
                            id = "plan-detail-quota",
                            text = headline(plan),
                            style = M3Typography.HeadlineMedium,
                            color = M3Colors.OnPrimaryContainer,
                        ),
                    )
                    chips(
                        plan,
                    ).takeIf { it.isNotEmpty() }?.let {
                        add(
                            RowComponent(id = "plan-detail-chips", spacing = 8, children = it),
                        )
                    }
                },
        )

    // What the hero says in one figure: the data when there is data, the minutes otherwise — a
    // plan with neither is not sold here.
    private fun headline(plan: Plan): String =
        when {
            plan.dataMb > 0 -> UsageUnits.megabytes(plan.dataMb)
            plan.minutes > 0 -> "${plan.minutes} min"
            else -> "${plan.messages} SMS"
        }

    // THE ATTRIBUTES AS CHIPS, and only the ones this plan has a fact for. The canvas draws
    // `30 days`, `5G where available`, `Hotspot allowed`; this build knows validity and zone and
    // nothing about the radio, so a chip it cannot vouch for is not drawn.
    private fun chips(plan: Plan): List<KompotComponent> =
        buildList {
            if (plan.validForDays > 0) add(chip("validity", "${plan.validForDays} days"))
            if (plan.zone != Zones.HOME) add(chip("zone", RoamingZoneNames.of(plan.zone)))
        }

    private fun chip(
        id: String,
        text: String,
    ): KompotComponent =
        SurfaceComponent(
            id = "plan-detail-chip-$id",
            density = SurfaceDensities.CHIP,
            children =
                listOf(
                    TextComponent(
                        id = "plan-detail-chip-$id-text",
                        text = text,
                        style = M3Typography.LabelMedium,
                        color = M3Colors.OnSurface,
                    ),
                ),
        )

    // THE TABLE: a white card of label/value rows with a hairline between them. The rows are what
    // the plan can answer — `Calls & SMS` says "Not included" rather than vanishing, because a
    // travel plan that omits the line looks like a screen that forgot to mention it.
    private fun includes(plan: Plan): KompotComponent =
        SurfaceComponent(
            id = "plan-detail-includes",
            dividers = true,
            spacing = 12,
            children =
                buildList {
                    if (plan.dataMb > 0) add(row("data", "Data", UsageUnits.megabytes(plan.dataMb)))
                    add(
                        row(
                            "calls",
                            "Calls & SMS",
                            when {
                                plan.minutes > 0 && plan.messages > 0 -> {
                                    "${plan.minutes} minutes · ${plan.messages} messages"
                                }

                                plan.minutes > 0 -> {
                                    "${plan.minutes} minutes"
                                }

                                plan.messages > 0 -> {
                                    "${plan.messages} messages"
                                }

                                else -> {
                                    "Not included"
                                }
                            },
                        ),
                    )
                    add(
                        row(
                            "activates",
                            "Activation",
                            if (plan.zone == Zones.HOME) "When the purchase completes" else "On first connection",
                        ),
                    )
                    if (plan.validForDays >
                        0
                    ) {
                        add(row("validity", "Runs for", "${plan.validForDays} days once it starts"))
                    }
                    if (plan.zone != Zones.HOME) add(row("zone", "Works in", RoamingZoneNames.of(plan.zone)))
                },
        )

    private fun row(
        id: String,
        label: String,
        value: String,
    ): KompotComponent =
        RowComponent(
            id = "plan-detail-$id",
            spacing = 12,
            children =
                listOf(
                    TextComponent(
                        id = "plan-detail-$id-label",
                        text = label,
                        style = M3Typography.BodyMedium,
                        color = M3Colors.OnSurfaceVariant,
                        modifiers = TAKES_THE_SPACE,
                    ),
                    TextComponent(
                        id = "plan-detail-$id-value",
                        text = value,
                        style = M3Typography.TitleSmall,
                        color = M3Colors.OnSurface,
                    ),
                ),
        )

    // PINNED, so a screen that scrolls never scrolls its one action away; `Charged once` and the
    // price on the line above it, which is the canvas's answer to "is this a subscription" asked
    // at the moment of paying.
    private fun footer(plan: Plan): KompotComponent =
        SurfaceComponent(
            id = "plan-detail-footer",
            pinned = true,
            spacing = 10,
            children =
                listOf(
                    RowComponent(
                        id = "plan-detail-charged",
                        spacing = 12,
                        children =
                            listOf(
                                TextComponent(
                                    id = "plan-detail-charged-label",
                                    text = "Charged once",
                                    style = M3Typography.BodyMedium,
                                    color = M3Colors.OnSurfaceVariant,
                                    modifiers = TAKES_THE_SPACE,
                                ),
                                TextComponent(
                                    id = "plan-detail-charged-price",
                                    text = MoneyFormat.format(plan.price),
                                    style = M3Typography.TitleMedium,
                                    color = M3Colors.OnSurface,
                                ),
                            ),
                    ),
                    ButtonComponent(
                        id = "plan-detail-buy",
                        text = "Buy for ${MoneyFormat.format(plan.price)}",
                        action = BuyPlanAction(plan.id),
                        modifiers = FILLS_THE_ROW,
                    ),
                ),
        )
}
