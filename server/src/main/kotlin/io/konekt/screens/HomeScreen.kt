package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.domain.Money
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.money.MoneyFormat

// The first screen, and the one that proves the loop: the server builds a tree, the client renders
// it, and nothing about the layout lives in the client.
//
// IT IS ASSEMBLED IN `:server` AND NOT IN A FEATURE, which is the one place that can see both halves
// of it. The balance belongs to the purchase feature's ledger and the counters to the usage feature,
// and a feature reaching for the other's repository to draw one screen is how two features become
// one. The composition root composes; that is what it is for.
object HomeScreen {
    fun build(
        balance: Money?,
        counters: List<UsageCounter>,
        cards: UsageCounterCards,
    ): KompotComponent =
        ColumnComponent(
            id = "home",
            spacing = 16,
            children =
                buildList {
                    addAll(balanceBlock(balance))

                    if (counters.isEmpty()) {
                        // NOT AN EMPTY COLUMN. A subscriber who has bought nothing has no counters,
                        // and a screen that draws nothing for them is indistinguishable from one that
                        // failed to load. Saying so, with somewhere to go, is the difference.
                        add(
                            BannerComponent(
                                id = "home-no-plans",
                                text = "No plan is active on this line yet.",
                                tone = MessageTones.INFO,
                                actionText = "See plans",
                                action = NavigateAction("app://plans"),
                            ),
                        )
                    } else {
                        // Ordered by the repository, which sorts by the enum rather than by the
                        // database — two screens that disagree about which counter comes first read
                        // as two products.
                        addAll(counters.map(cards::of))
                    }
                },
        )

    private fun balanceBlock(balance: Money?): List<KompotComponent> {
        // A balance the server could not read is left out rather than drawn as zero. Zero is a fact
        // about an account and "we could not tell" is not, and a subscriber who reads the first when
        // the second is true tops up money they already have.
        balance ?: return emptyList()

        return listOf(
            TextComponent(
                id = "balance-label",
                text = "Balance",
                style = M3Typography.LabelMedium,
                color = M3Colors.OnSurfaceVariant,
            ),
            TextComponent(
                id = "balance-amount",
                // Formatted on the server, because it is the only side that can (D15). The client
                // renders a string and therefore cannot format it inconsistently.
                text = MoneyFormat.format(balance),
                style = M3Typography.DisplaySmall,
                color = M3Colors.OnSurface,
            ),
        )
    }
}
