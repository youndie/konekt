package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceTones
import io.konekt.domain.Money
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.shell.shared.api.ORDERS_DEEPLINK
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.money.MoneyFormat
import io.konekt.roaming.RoamingPackageCards

// The first screen, and the one that proves the loop: the server builds a tree, the client renders
// it, and nothing about the layout lives in the client.
//
// IT IS ASSEMBLED IN `:server` AND NOT IN A FEATURE, which is the one place that can see both halves
// of it. The balance belongs to the purchase feature's ledger and the counters to the usage feature,
// and a feature reaching for the other's repository to draw one screen is how two features become
// one. The composition root composes; that is what it is for.
object HomeScreen {
    fun build(
        // WHOSE LINE THIS IS. The canvas puts it under the balance, and it is the one fact on this
        // screen that answers a question a subscriber with two SIMs actually asks. Nullable because
        // the screen is worth drawing without it: a number the server could not read is left out
        // rather than drawn as a blank, for the same reason the balance is.
        msisdn: String?,
        balance: Money?,
        counters: List<UsageCounter>,
        cards: UsageCounterCards,
        // Roaming packages sit on the SAME screen as the home counters rather than behind a tab,
        // because from the subscriber's side they are one question — what have I got. A separate
        // roaming screen would mean a package bought for a trip is invisible on the screen they
        // actually open, which is the failure mode this feature exists to fix.
        packages: List<RoamingPackage> = emptyList(),
        roamingCards: RoamingPackageCards? = null,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "home",
            spacing = 16,
            children =
                buildList {
                    balanceCard(msisdn, balance)?.let(::add)

                    if (counters.isEmpty() && packages.isEmpty()) {
                        // NOT AN EMPTY COLUMN. A subscriber who has bought nothing has no counters,
                        // and a screen that draws nothing for them is indistinguishable from one that
                        // failed to load. Saying so, with somewhere to go, is the difference.
                        add(
                            BannerComponent(
                                id = "home-no-plans",
                                text = "No plan is active on this line yet.",
                                tone = MessageTones.INFO,
                                actionText = "See plans",
                                action = NavigateAction(PLANS_DEEPLINK),
                            ),
                        )
                    } else {
                        // Ordered by the repository, which sorts by the enum rather than by the
                        // database — two screens that disagree about which counter comes first read
                        // as two products.
                        addAll(counters.map(cards::of))

                        // AFTER the home counters, always. What a subscriber is spending right now is
                        // the answer to the question they opened the screen with; a package for a trip
                        // in three weeks is context.
                        if (roamingCards != null) addAll(packages.map(roamingCards::of))

                        // AFTER what the subscriber already has, because that is the order the
                        // question comes in: what have I got, then what else is there. The empty
                        // case above already offers the catalogue in its banner, so this is the
                        // other half of the same door rather than a second one.
                        add(
                            ButtonComponent(
                                id = "home-buy",
                                text = "Buy a package",
                                action = NavigateAction(PLANS_DEEPLINK),
                                modifiers = FILLS_THE_ROW,
                            ),
                        )
                    }

                    // THE SHELL, added last and hoisted by the client out of the tree it arrived in.
                    // In the tree rather than fetched separately so the SERVER decides which tab is
                    // current: it is the only side that knows which screen it just built.
                    nav?.let(::add)
                },
        )

    // ONE NODE, NOT FOUR SIBLINGS, and until now it was four.
    //
    // The canvas draws the balance as a filled, rounded card holding the label, the amount, the
    // number and both controls; the served tree put them straight into the screen's own column, so
    // there was nothing grouping them and nothing standing them on a ground. That is not a card drawn
    // wrongly — it is the absence of a card, and it was the first thing a person noticed opening the
    // running application.
    //
    // The GROUND is a role rather than a colour and the CORNER is not said at all: the served brand
    // kit decides the first and the client's shape scale the second. See `SurfaceComponent` for why
    // this needs a component of konekt's own and for the upstream ask that would delete it.
    private fun balanceCard(
        msisdn: String?,
        balance: Money?,
    ): KompotComponent? {
        // A balance the server could not read is left out rather than drawn as zero. Zero is a fact
        // about an account and "we could not tell" is not, and a subscriber who reads the first when
        // the second is true tops up money they already have.
        balance ?: return null

        return SurfaceComponent(
            id = "balance",
            // THE ACCENTED ONE, and the only one on this screen: the canvas stands the balance on
            // `primary_container` and everything else on the quiet card, which is what makes it read
            // as the thing the screen is about.
            tone = SurfaceTones.ACCENT,
            spacing = 4,
            children =
                buildList {
                    add(
                        TextComponent(
                            id = "balance-label",
                            text = "Balance",
                            style = M3Typography.LabelMedium,
                            color = M3Colors.OnPrimaryContainer,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "balance-amount",
                            // Formatted on the server, because it is the only side that can (D15).
                            // The client renders a string and therefore cannot format it
                            // inconsistently.
                            text = MoneyFormat.format(balance),
                            style = M3Typography.DisplaySmall,
                            color = M3Colors.OnPrimaryContainer,
                        ),
                    )
                    msisdn?.let {
                        add(
                            TextComponent(
                                id = "balance-msisdn",
                                // The plus put back on, exactly as the profile screen does it and for
                                // the same reason: `Msisdn` stores digits, and "79990001234" reads as
                                // a local number in one country and a wrong one everywhere else.
                                text = "+$it",
                                style = M3Typography.BodyMedium,
                                color = M3Colors.OnPrimaryContainer,
                            ),
                        )
                    }
                    add(
                        // THE TWO THINGS A SUBSCRIBER DOES WITH A BALANCE, beside it because that is
                        // where they are asked for. `Top up` was missing for a build, and the comment
                        // that explained its absence had outlived its reason — see `B-40`.
                        RowComponent(
                            id = "balance-actions",
                            spacing = 8,
                            children =
                                listOf(
                                    ButtonComponent(
                                        id = "balance-top-up",
                                        text = "Top up",
                                        action = NavigateAction(TOP_UP_DEEPLINK),
                                    ),
                                    ButtonComponent(
                                        id = "balance-history",
                                        text = "History",
                                        action = NavigateAction(ORDERS_DEEPLINK),
                                    ),
                                ),
                        ),
                    )
                },
        )
    }
}
