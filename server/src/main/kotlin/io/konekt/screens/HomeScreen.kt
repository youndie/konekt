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
import io.konekt.components.ButtonEmphasis
import io.konekt.components.MessageTones
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceTones
import io.konekt.domain.Money
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.shared.api.ESIM_INSTALL_DEEPLINK
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.roaming.shared.api.ROAMING_DEEPLINK
import io.konekt.feature.shell.shared.api.ORDERS_DEEPLINK
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.money.DayFormat
import io.konekt.money.MoneyFormat
import io.konekt.roaming.RoamingPackageCards

// The first screen, and the one that proves the loop: the server builds a tree, the client renders
// it, and nothing about the layout lives in the client.
//
// IT IS ASSEMBLED IN `:server` AND NOT IN A FEATURE, which is the one place that can see both halves
// of it. The balance belongs to the purchase feature's ledger and the counters to the usage feature,
// and a feature reaching for the other's repository to draw one screen is how two features become
// one. The composition root composes; that is what it is for.
// THE TWO CARD FACTORIES ARE HELD RATHER THAN PASSED IN (`B-96`). They are renderers — they turn a
// counter into a component — so they belong to this side of the line and not in the signature of a
// screen builder. What used to travel beside them, and does not any more, is the data they render
// against: that arrives in the view, with one instant for all of it.
class HomeScreen(
    private val cards: UsageCounterCards,
    private val roamingCards: RoamingPackageCards,
) {
    fun build(
        view: HomeView,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "home",
            spacing = 16,
            children =
                buildList {
                    // THE HEADER, and it is the brand's name and nothing else.
                    //
                    // Section 01 draws two more things beside it: a plain chip and an avatar carrying
                    // the subscriber's initials. Neither is drawn, and the reason is the same one that
                    // kept the whole header out until now — `subscriber` holds an msisdn and nothing
                    // else, so initials would have to be invented. A circle with made-up letters in it
                    // is a mockup wearing the product's clothes.
                    //
                    // The name itself stopped being missing when the brand kit gained a `displayName`:
                    // it is a deployment fact, it lives in the file an operator already edits, and it
                    // needed no wire type of its own because the server builds this screen.
                    view.brandName?.let {
                        add(
                            TextComponent(
                                id = "home-brand",
                                text = it,
                                style = M3Typography.HeadlineSmall,
                                color = M3Colors.OnSurface,
                            ),
                        )
                    }
                    balanceCard(view.msisdn, view.balance)?.let(::add)

                    if (view.counters.isEmpty() && view.packages.isEmpty()) {
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
                        // ONE CARD FOR THE THREE, which is what the canvas draws and what three
                        // separate cards read as: three unrelated things. `B-60` settled that the
                        // container for it exists — `surface` — and that what blocked it was the
                        // domain rather than the wire.
                        //
                        // Ordered by the repository, which sorts by the enum rather than by the
                        // database — two screens that disagree about which counter comes first read
                        // as two products.
                        add(allowanceCard(view))

                        // AFTER the home counters, always. What a subscriber is spending right now is
                        // the answer to the question they opened the screen with; a package for a trip
                        // in three weeks is context.
                        addAll(view.packages.map { roamingCards.of(it, view.at) })

                        // AND THE WAY TO THE TRAVEL SCREEN, drawn ALWAYS — which is not what it was
                        // and is what the reachability guard caught.
                        //
                        // `B-88` gave roaming a screen of its own, and this door was conditional on
                        // the subscriber already having a package. So the screen's EMPTY state — "no
                        // travel package on this line yet", with the way to the catalogue — could
                        // never be reached by anybody: the only door to it was closed exactly when it
                        // was the state you would see. `EveryScreenIsReachableTest` said so in one
                        // line, *reachable from nowhere and not declared: app://roaming*, and it was
                        // right about the product and not only about the graph.
                        //
                        // The CARDS stay conditional: a package bought for a trip must be visible on
                        // the screen somebody opens, and a subscriber with none needs no empty list
                        // here — that is what the screen behind this banner is for.
                        add(
                            BannerComponent(
                                id = "home-roaming",
                                text = "Going abroad? See what you have for each trip.",
                                tone = MessageTones.INFO,
                                action = NavigateAction(ROAMING_DEEPLINK),
                                actionText = "Travel packages",
                            ),
                        )

                        // SOMETHING BOUGHT AND NOT YET INSTALLED, offered here because this is the
                        // screen a subscriber opens.
                        //
                        // Section 01 draws it as a row with `Install`, and until now the install flow
                        // could be reached from exactly two places — the purchase result, and a
                        // history row once one carried an action. Both are places somebody has to
                        // think to go. An allowance that cannot be used until a profile is installed
                        // is the one thing on this screen that is not about what they have but about
                        // what they cannot yet use.
                        //
                        // Drawn on the HOLDINGS and not on the roaming packages: what makes an eSIM
                        // installable has nothing to do with where the allowance works, and a home
                        // bundle needs a profile exactly as much as a trip does. Tying it to roaming
                        // would have been the canvas's example mistaken for the rule.
                        //
                        // TWO STATES, AND THE SECOND ONE USED TO HIDE THE DOOR. The condition was
                        // `held == 0`, so the banner appeared only for a line with no profile at all —
                        // and vanished the moment one was issued, which is precisely when there is
                        // something to install and a subscriber who has paid for it. The heading above
                        // this block already said "something bought and not yet installed"; the
                        // condition said something else (`B-69`).
                        installBanner(view.esims)?.let(::add)

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
                    // THE HEAD, AS THE CANVAS DRAWS IT: the label and the amount on the left, the
                    // number on the right of the same line. It was three stacked texts, which put the
                    // number under the money and made the block a list rather than a card with a
                    // figure on it.
                    //
                    // The left column carries the weight, so the number sits against the far edge —
                    // there is no `space-between` on the wire and none is needed for two items.
                    add(
                        RowComponent(
                            id = "balance-head",
                            spacing = 12,
                            children =
                                listOfNotNull(
                                    ColumnComponent(
                                        id = "balance-figure",
                                        spacing = 2,
                                        modifiers = TAKES_THE_SPACE,
                                        children =
                                            listOf(
                                                TextComponent(
                                                    id = "balance-label",
                                                    text = "Balance",
                                                    style = M3Typography.LabelMedium,
                                                    color = M3Colors.OnPrimaryContainer,
                                                ),
                                                TextComponent(
                                                    id = "balance-amount",
                                                    // Formatted on the server, because it is the only
                                                    // side that can (D15). The client renders a string
                                                    // and cannot format it inconsistently.
                                                    text = MoneyFormat.format(balance),
                                                    style = M3Typography.DisplaySmall,
                                                    color = M3Colors.OnPrimaryContainer,
                                                ),
                                            ),
                                    ),
                                    msisdn?.let {
                                        TextComponent(
                                            id = "balance-msisdn",
                                            // The plus put back on, exactly as the profile screen does
                                            // it and for the same reason: `Msisdn` stores digits, and
                                            // "79990001234" reads as a local number in one country and
                                            // a wrong one everywhere else.
                                            text = "+$it",
                                            style = M3Typography.BodyMedium,
                                            color = M3Colors.OnPrimaryContainer,
                                        )
                                    },
                                ),
                        ),
                    )
                    add(
                        // THE TWO THINGS A SUBSCRIBER DOES WITH A BALANCE, beside it because that is
                        // where they are asked for. `Top up` was missing for a build, and the comment
                        // that explained its absence had outlived its reason — see `B-40`.
                        RowComponent(
                            id = "balance-actions",
                            spacing = 8,
                            children =
                                listOf(
                                    // TOP UP TAKES THE ROW AND HISTORY HUGS ITS WORD. Two buttons of
                                    // equal weight is the defect `B-71` fixed on the purchase result,
                                    // met again here: where one control is the thing to press, drawing
                                    // both the same size makes the screen ask a question instead of
                                    // offering an answer.
                                    ButtonComponent(
                                        id = "balance-top-up",
                                        text = "Top up",
                                        action = NavigateAction(TOP_UP_DEEPLINK),
                                        modifiers = TAKES_THE_SPACE,
                                    ),
                                    ButtonComponent(
                                        id = "balance-history",
                                        text = "History",
                                        action = NavigateAction(ORDERS_DEEPLINK),
                                        // QUIET, which is the other half of the same decision as the
                                        // weight above. Width alone left two buttons the same colour,
                                        // and the canvas draws this one on a pale ground precisely so
                                        // that the eye lands on `Top up`. Same vocabulary `B-71` used
                                        // on the purchase result rather than a colour chosen here.
                                        variant = ButtonEmphasis.QUIET,
                                    ),
                                ),
                        ),
                    )
                },
        )
    }

    // THE THREE ALLOWANCES UNDER ONE HEAD, per the canvas — and the head is where this build and the
    // drawing part company.
    //
    // The canvas writes `Smart 20 · renews 12 Sep`. Neither half can be said here and both were
    // checked rather than assumed: `UsageCounter` carries a subscriber, a kind, two numbers and
    // `startedAt`, and NO reference to the plan that granted it — so there is no package to name —
    // and nothing in this product renews, so there is no renewal date. `B-60` recorded exactly this
    // and left the grouping open because of it.
    //
    // So the head says what IS true: that this is the allowance, and WHEN it started. The date is
    // real — `startedAt` is what the projection on every card is already computed from — and it sits
    // in the slot the canvas puts a date in. Inventing a renewal to fill that slot would be a mockup
    // wearing the product's clothes, which is the same argument that keeps the avatar off this
    // screen.
    private fun allowanceCard(view: HomeView): KompotComponent =
        SurfaceComponent(
            id = "allowance",
            tone = SurfaceTones.NEUTRAL,
            spacing = 16,
            children =
                buildList {
                    add(
                        RowComponent(
                            id = "allowance-head",
                            spacing = 12,
                            children =
                                listOfNotNull(
                                    TextComponent(
                                        id = "allowance-title",
                                        text = "Your allowance",
                                        style = M3Typography.TitleMedium,
                                        color = M3Colors.OnSurface,
                                        modifiers = TAKES_THE_SPACE,
                                    ),
                                    // The earliest start among the three: they are granted together
                                    // by one purchase today, and `minOf` is what keeps the line true
                                    // rather than arbitrary on the day they are not.
                                    view.counters.minByOrNull { it.startedAt }?.let {
                                        TextComponent(
                                            id = "allowance-since",
                                            text = "since ${DayFormat.dayAndMonth(it.startedAt)}",
                                            style = M3Typography.BodySmall,
                                            color = M3Colors.OnSurfaceVariant,
                                        )
                                    },
                                ),
                        ),
                    )
                    addAll(view.counters.map { cards.of(it, view.at) })
                },
        )

    // The door to the install flow, or none. Two sentences because the two states are different
    // errands: one issues a profile, the other shows the code for one that already exists, and a
    // subscriber told "your line has no eSIM yet" about a profile they have bought would reasonably
    // think the purchase failed.
    private fun installBanner(esims: EsimHoldings): KompotComponent? =
        when {
            // `needsInstalling` decides WHETHER, and the branches below decide what to say. The
            // purchase result asks the same property, which is the whole point of it being one:
            // that screen used to offer the wizard on every completed purchase and mint a profile
            // each time (`B-78`).
            !esims.needsInstalling -> {
                null
            }

            esims.held == 0 -> {
                BannerComponent(
                    id = "home-install-esim",
                    text = "Your line has no eSIM yet. Install one to start using what you have bought.",
                    tone = MessageTones.INFO,
                    actionText = "Install eSIM",
                    action = NavigateAction(ESIM_INSTALL_DEEPLINK),
                )
            }

            esims.awaitingInstall > 0 -> {
                BannerComponent(
                    id = "home-install-esim",
                    // Not "ready to install": a profile still being prepared is in this bucket too.
                    // What is true of both is that it is not on the phone.
                    text = "Your eSIM is not installed yet. Install it to start using what you have bought.",
                    tone = MessageTones.INFO,
                    actionText = "Install eSIM",
                    action = NavigateAction(ESIM_INSTALL_DEEPLINK),
                )
            }

            // Unreachable given the guard above, and kept because the `when` is over a state rather
            // than over two cases: a bucket added to `EsimHoldings` lands here rather than in one of
            // the sentences above, which is the right place for something nobody has written copy for.
            else -> {
                null
            }
        }
}
