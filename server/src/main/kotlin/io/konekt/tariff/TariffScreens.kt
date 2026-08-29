package io.konekt.tariff

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.components.PlanCardComponent
import io.konekt.components.PlanStates
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.tariff.shared.api.ChangeTariffAction
import io.konekt.feature.tariff.shared.api.ConfirmTariffChangeAction
import io.konekt.feature.usage.server.data.UsageUnits
import io.konekt.money.DayFormat
import io.konekt.money.MoneyFormat
import io.konekt.screens.FILLS_THE_ROW

// THE TARIFF CATALOGUE, which three tariffs sat in and nothing displayed until `B-86`.
//
// NO NEW WIRE TYPE. A tariff is drawn with `PlanCardComponent` — title, price, what it includes, a
// badge and an action — because that is what the component already says, and a `TariffCardComponent`
// would be a client release for a card that differs from an existing one in nothing but the word.
// The dictionary is the API (§1.5), so adding to it is the expensive move and reaching for it when a
// type already fits is how a dictionary stops being a vocabulary and becomes a list of screens.
object TariffsScreen {
    fun build(
        tariffs: List<Tariff>,
        currentTariffId: String,
        pending: TariffChangeRecord?,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "tariffs",
            spacing = 12,
            children =
                buildList {
                    add(
                        TextComponent(
                            id = "tariffs-title",
                            text = "Your tariff",
                            style = M3Typography.HeadlineSmall,
                            color = M3Colors.OnSurface,
                        ),
                    )

                    // A CHANGE ALREADY UNDER WAY IS THE FIRST THING ON THE SCREEN, and it carries the
                    // way back to it. Without this a subscriber who asked for a change and left the
                    // application has no route to the confirmation they were asked for — the change
                    // exists, waits, and is unreachable, which is the shape `B-86` exists to end.
                    pending?.let { add(pendingBanner(it, tariffs)) }

                    addAll(tariffs.map { card(it, currentTariffId, pending != null) })

                    nav?.let(::add)
                },
        )

    private fun pendingBanner(
        pending: TariffChangeRecord,
        tariffs: List<Tariff>,
    ): KompotComponent =
        BannerComponent(
            id = "tariffs-pending",
            text =
                "A change to ${nameOf(pending.toTariffId, tariffs)} is waiting for your confirmation, " +
                    "and takes effect on ${DayFormat.dayAndMonth(pending.effectiveAt)}.",
            tone = MessageTones.INFO,
            // The way back to it, as an ACTION rather than a `navigate`: the address of a change is
            // built from its id, and the one place that knows how to spell it is the client's handler
            // — the same arrangement buying uses for an order.
            action = ConfirmTariffChangeAction(pending.changeId),
            actionText = "Review it",
        )

    private fun card(
        tariff: Tariff,
        currentTariffId: String,
        changePending: Boolean,
    ): PlanCardComponent {
        val current = tariff.id == currentTariffId
        return PlanCardComponent(
            id = "tariff-${tariff.id}",
            title = tariff.title,
            // Per MONTH, and the word is in the string because the number means nothing without it: a
            // tariff at $12 beside a roaming package at $12 is not the same offer.
            priceText = "${MoneyFormat.format(tariff.monthlyPrice)} / month",
            quotaTexts = listOf(UsageUnits.megabytes(tariff.dataMb)),
            // THE CURRENT ONE IS MARKED, which is the acceptance criterion and also the only way the
            // screen answers the question a subscriber opens it with.
            badgeText = if (current) "Your tariff" else null,
            // AVAILABLE EVEN FOR THE CURRENT ONE, and the first version of this said `SOLD_OUT`
            // because it makes a card unpressable. It also makes the client draw the words **Sold
            // out**, in red, over the subscriber's own tariff — and it takes the badge's slot, so
            // "Your tariff" never appeared at all. Two states share one line in the renderer.
            //
            // Nothing in a tree assertion could see that: the server said `state = sold_out` and the
            // test asked whether the badge and the action were right, and both were. It took a
            // screenshot from a device. The lesson is narrower than "test the render" — it is that a
            // vocabulary value carries the MEANING the other side draws, and `sold_out` means *not
            // for sale*, which a tariff somebody is on is not.
            //
            // What actually makes the card unpressable is `action == null`, on its own.
            state = PlanStates.AVAILABLE,
            // NO ACTION ON THE CURRENT ONE, and none at all while a change is already waiting. The
            // second half is not tidiness: the saga refuses a second change with one pending, and a
            // card that accepts a press and is then refused is worse than one that does not accept it.
            // The client decides what is pressable and the server decides what is offered, and
            // neither may be the only one that knows (`B-68`).
            action = if (current || changePending) null else ChangeTariffAction(tariff.id),
        )
    }

    private fun nameOf(
        tariffId: String,
        tariffs: List<Tariff>,
    ): String = tariffs.firstOrNull { it.id == tariffId }?.title ?: tariffId
}

// ONE TARIFF CHANGE, AS A SCREEN — the confirmation this build could previously only demonstrate in a
// harness where confirming was a function call.
//
// WHY THIS SCREEN IS WORTH MORE THAN THE PURCHASE RESULT IT RESEMBLES: a purchase's confirmation asks
// "spend this?", and this one asks "change what you are on?". Different refusal, different reversal,
// and the second is what shows petich's suspend/resume is not being used for one shape of transaction
// only.
object TariffChangeScreen {
    fun build(
        view: TariffChangeView,
        tariffs: List<Tariff>,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "tariff-change",
            spacing = 12,
            children =
                buildList {
                    add(
                        TextComponent(
                            id = "tariff-change-title",
                            text = titleOf(view),
                            style = M3Typography.HeadlineSmall,
                            color = M3Colors.OnSurface,
                        ),
                    )

                    // WHAT CHANGES AND WHEN, as two rows and not one sentence. Both tariffs are named
                    // because both are TRUE until the boundary — which is the whole of `B-21`'s first
                    // acceptance criterion and the thing a date alone does not say.
                    add(row("from", "Now on", nameOf(view.currentTariffId, tariffs)))
                    add(row("to", "Changing to", nameOf(view.requestedTariffId, tariffs)))
                    add(row("when", "Takes effect", DayFormat.dayAndMonth(view.effectiveAt)))

                    add(explanation(view))
                    control(view)?.let(::add)

                    nav?.let(::add)
                },
        )

    private fun titleOf(view: TariffChangeView): String =
        when {
            view.requiredAction != null -> "Confirm the change"
            view.status == OrderStatus.COMPLETED -> "Change confirmed"
            view.status == OrderStatus.COMPENSATED -> "Change reversed"
            view.status == OrderStatus.REJECTED -> "Change refused"
            else -> "Your change"
        }

    // THE SENTENCE THAT SAYS WHAT HAPPENS NEXT, per state. A screen that draws two names and a date
    // and stops leaves the subscriber to work out whether anything is still expected of them.
    private fun explanation(view: TariffChangeView): KompotComponent =
        when {
            view.requiredAction != null -> {
                BannerComponent(
                    id = "tariff-change-explain",
                    text =
                        "Nothing changes until you confirm. Your current tariff runs to " +
                            "${DayFormat.dayAndMonth(view.effectiveAt)} either way.",
                    tone = MessageTones.INFO,
                )
            }

            view.status == OrderStatus.COMPLETED -> {
                BannerComponent(
                    id = "tariff-change-explain",
                    text = "You stay on your current tariff until ${DayFormat.dayAndMonth(view.effectiveAt)}.",
                    // INFO AND NOT A SUCCESS TONE, because there is no success tone: the vocabulary
                    // is INFO, LOW and ERROR, and inventing a fourth would be a client release for
                    // one banner's colour. The words carry the outcome.
                    tone = MessageTones.INFO,
                )
            }

            // REFUSED AND REVERSED READ DIFFERENTLY, and `B-68` is why: a subscriber told only that
            // something did not work cannot tell whether to try again. Nothing was billed either way
            // — a tariff change moves no money until the boundary — and saying so is the whole
            // reassurance this screen can offer.
            view.status == OrderStatus.COMPENSATED -> {
                BannerComponent(
                    id = "tariff-change-explain",
                    // COMPENSATED is a step that ran and was undone; REJECTED is one that refused
                    // before anything happened. `B-41` settled the vocabulary and `B-68` settled why
                    // they must read differently: a subscriber told only that something did not work
                    // cannot tell whether trying again is worth anything.
                    text = "The change was reversed and you stay on your current tariff. Nothing was billed.",
                    tone = MessageTones.ERROR,
                )
            }

            view.status == OrderStatus.REJECTED -> {
                BannerComponent(
                    id = "tariff-change-explain",
                    text = "The change could not be made and you stay on your current tariff. Nothing was billed.",
                    tone = MessageTones.ERROR,
                )
            }

            else -> {
                BannerComponent(
                    id = "tariff-change-explain",
                    text = "This change is still being processed.",
                    tone = MessageTones.INFO,
                )
            }
        }

    // THE CONFIRMATION, and it is the only control this screen ever has. There is no way out button:
    // the bottom bar is the way out of every screen that is not a flow, and a second primary beside
    // the confirmation is the defect `B-71` closed on the purchase result.
    private fun control(view: TariffChangeView): KompotComponent? =
        view.requiredAction?.let {
            ButtonComponent(
                id = "tariff-change-confirm",
                text = "Confirm",
                action = ConfirmTariffChangeAction(view.changeId),
                modifiers = FILLS_THE_ROW,
            )
        }

    private fun row(
        id: String,
        label: String,
        value: String,
    ): KompotComponent =
        ColumnComponent(
            id = "tariff-change-$id",
            spacing = 2,
            children =
                listOf(
                    TextComponent(
                        id = "tariff-change-$id-label",
                        text = label,
                        style = M3Typography.LabelMedium,
                        color = M3Colors.OnSurfaceVariant,
                    ),
                    TextComponent(
                        id = "tariff-change-$id-value",
                        text = value,
                        style = M3Typography.TitleMedium,
                        color = M3Colors.OnSurface,
                    ),
                ),
        )

    private fun nameOf(
        tariffId: String,
        tariffs: List<Tariff>,
    ): String = tariffs.firstOrNull { it.id == tariffId }?.title ?: tariffId
}
