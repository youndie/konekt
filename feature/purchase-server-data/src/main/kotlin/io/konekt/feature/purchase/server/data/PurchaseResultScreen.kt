package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.SizeType
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.ButtonEmphasis
import io.konekt.components.IconComponent
import io.konekt.components.MessageTones
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceTones
import io.konekt.components.VectorIcon
import io.konekt.domain.Money
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.shared.api.ESIM_INSTALL_DEEPLINK
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.OrderView
import io.konekt.feature.purchase.server.domain.PurchaseRefusals
import io.konekt.feature.purchase.server.domain.Reversal
import io.konekt.feature.purchase.shared.api.ConfirmPurchaseAction
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.konekt.feature.shell.shared.api.HOME_DEEPLINK
import io.konekt.money.DayFormat
import io.konekt.money.MoneyFormat

// The screen a subscriber lands on after a purchase ends, built on the server as a component tree.
//
// The rollback branch is the one this exists for, and the canvas is specific about why: a rollback is
// stated IN MONEY — what was reversed, what the balance is now, and the reference to quote — rather
// than as an apology. A subscriber who can reconcile a reversal against their bank does not ring
// support; one who is told "something went wrong" does.
object PurchaseResultScreen {
    // THE `when` BELOW HAS NO `else`, and the one that used to be there is what this is about.
    //
    // `AWAITING_CONFIRMATION` fell into it and drew "Confirming with the payment provider — keep the
    // app open". Nothing was being confirmed with any provider: the saga was suspended waiting for
    // the SUBSCRIBER, and the screen told them to wait for something that was never coming while the
    // window ran out and the order rolled back. Two different states under one word.
    //
    // The neighbouring file already carries this lesson — `HistoryScreen` has "NO `else`, in both
    // `when`s below" and the story of the `else` that drew a rejected order as pending. The same
    // mistake, in the file next to it, by the same mechanism. An exhaustive `when` over an enum makes
    // the next state a compile error instead.
    fun build(
        order: OrderView,
        reversal: Reversal?,
        balance: Money?,
        // WHAT THE LINE HOLDS, for the completed branch alone. Defaulted to a line with nothing so
        // the many tests about refusals and rollbacks say nothing about eSIMs — and so that a caller
        // that forgets it offers the install rather than hiding it, which is the safer way round for
        // a subscriber who has none.
        esims: EsimHoldings = EsimHoldings.none,
    ): KompotComponent =
        ColumnComponent(
            id = "purchase-result",
            spacing = 16,
            children =
                when (order.status) {
                    OrderStatus.COMPENSATED -> reversed(order, reversal, balance)

                    OrderStatus.COMPLETED -> completed(order, balance, esims)

                    OrderStatus.REJECTED -> rejected(order, balance)

                    OrderStatus.AWAITING_CONFIRMATION -> awaitingConfirmation(order, balance)

                    // The two that really are "in flight": the saga is running, or a compensating
                    // step itself failed and a person has to look. Neither is anything a subscriber
                    // can act on, which is exactly what separates them from the branch above.
                    OrderStatus.PENDING, OrderStatus.COMPENSATING -> inFlight(order)
                },
        )

    private fun reversed(
        order: OrderView,
        reversal: Reversal?,
        balance: Money?,
    ): List<KompotComponent> =
        buildList {
            add(
                BannerComponent(
                    id = "purchase-reversed",
                    // The provider's own words when there are any. A purchase abandoned at the
                    // confirmation has no provider to quote and does not invent one.
                    text = order.declineReason ?: "The confirmation window passed, so the purchase was not completed.",
                    tone = MessageTones.ERROR,
                ),
            )

            // THE AMOUNT COMES FROM THE LEDGER, not from the order's price. They agree today. The
            // ledger is the record of what happened and the price is the record of what was asked
            // for, and the day a partial reversal exists only one of the two is still right.
            val returned = reversal?.amount ?: order.payload.price

            add(
                OrderRowComponent(
                    id = "order-${order.orderId}",
                    // Short enough to read aloud to support, which is the whole reason it is on the
                    // screen at all.
                    reference = order.orderId.take(8),
                    title = order.payload.planTitle,
                    dateText = reversal?.at?.let(DayFormat::dayAndMonth) ?: "",
                    amountText = MoneyFormat.format(returned, signed = true),
                    status = OrderStatuses.COMPENSATED,
                    statusText = "Reversed",
                    // The canvas's sentence, with the date the money actually moved rather than the
                    // date the order was made.
                    noteText =
                        reversal?.let {
                            "${MoneyFormat.format(it.amount)} returned to balance on ${DayFormat.dayAndMonth(it.at)}" +
                                " — nothing was activated."
                        },
                ),
            )

            // STATED AS A CURRENT FACT, not as "back to where it was".
            //
            // The canvas writes "your balance is back to where it was", and the server cannot promise
            // that: between the reversal and this render the balance may have moved for an unrelated
            // reason — another purchase, a top-up — and the sentence would then be false while every
            // number on the screen was true. What can be promised is what was returned and what the
            // balance is now, which are two facts rather than a claim about their relationship.
            balance?.let {
                add(
                    TextComponent(
                        id = "balance-now",
                        text = "Your balance is now ${MoneyFormat.format(it)}.",
                    ),
                )
            }

            // AND A WAY OFF IT, which this branch alone did not have.
            //
            // Five of the six states carried one and the rollback did not — the one state this
            // product exists to demonstrate, and the one a subscriber arrives at with a question. It
            // was a screen with a banner, a row, a sentence and nothing to press: a dead end at the
            // end of the flow the whole build is about.
            //
            // The others were fixed together when the purchase result gained its exits, and this
            // branch is longer than the rest, so the missing call is at the bottom of a `buildList`
            // rather than in a `listOf` where it would have been one line out of four.
            addAll(controls("purchase-reversed-back", "Back"))
        }

    // A WAY OFF THIS SCREEN, and every state needs one. The purchase result is reachable from the
    // catalogue and carries no tab bar, so without one a subscriber who bought something had nowhere
    // to go: pressing a plan was a one-way door.
    //
    // THE FOOT OF THE SCREEN, and the way out's weight is DERIVED here rather than chosen by each
    // branch.
    //
    // A way out is the answer when nothing else on the screen is, and the second option when
    // something is — which the `quiet` variant existed for and which four of the five states got
    // right by hand. The completed purchase did not: `Install eSIM` and `Done` were both filled
    // primaries of the same width and colour, so the screen asked one question twice (`B-71`).
    //
    // Deciding it per branch is what produced that, and would produce it again on the next state to
    // gain a second control. So no branch decides: it hands over whatever else it has to press, and
    // the rule is applied in one place.
    private fun controls(
        wayOutId: String,
        wayOutText: String,
        vararg actions: KompotComponent?,
    ): List<KompotComponent> {
        val present = actions.filterNotNull()

        return present +
            ButtonComponent(
                id = wayOutId,
                text = wayOutText,
                // HOME AND NOT BACK. The purchase result is reachable from the catalogue and carries
                // no tab bar — it is not a tab — so without this a subscriber who bought something
                // had nowhere to go. Pressing it lands them where the tabs are.
                action = NavigateAction(HOME_DEEPLINK),
                variant = if (present.isEmpty()) ButtonEmphasis.PRIMARY else ButtonEmphasis.QUIET,
                modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
            )
    }

    // THE CANVAS'S OUTCOME (`B-114`, block 2): a check in a primary disc, `Paid.` as the headline,
    // one paragraph saying what happens next, and a receipt — order, charged, balance left — as a
    // table with hairlines. It was a banner and an order row, which read as a system message about
    // something that had happened to somebody else.
    private fun completed(
        order: OrderView,
        balance: Money?,
        esims: EsimHoldings,
    ): List<KompotComponent> =
        listOf(
            IconComponent(id = "purchase-mark", icon = CHECK, tone = MessageTones.INFO),
            TextComponent(
                id = "purchase-headline",
                text = if (esims.needsInstalling) "Paid. eSIM is ready to install." else "Paid.",
                style = M3Typography.HeadlineSmall,
                color = M3Colors.OnSurface,
            ),
            TextComponent(
                id = "purchase-completed",
                text =
                    if (esims.needsInstalling) {
                        "${order.payload.planTitle}. Install now or later from Orders — the plan starts " +
                            "counting on first connection, not now."
                    } else {
                        "${order.payload.planTitle} is on your line and counting."
                    },
                style = M3Typography.BodyMedium,
                color = M3Colors.OnSurfaceVariant,
            ),
            receipt(
                "purchase-receipt",
                "Order" to order.orderId.take(8),
                "Charged" to "${MoneyFormat.format(order.payload.price)} from balance",
                "Balance left" to balance?.let(MoneyFormat::format),
            ),
            *controls(
                "purchase-done",
                "Done",
                esims.needsInstalling.takeIf { it }?.let {
                    ButtonComponent(
                        id = "purchase-install",
                        text = "Install eSIM",
                        action = NavigateAction(ESIM_INSTALL_DEEPLINK),
                        modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
                    )
                },
            ).toTypedArray(),
        )

    // A RECEIPT: label left, value right, a hairline between rows. A row whose value is not known —
    // a balance the screen was not given — is left out rather than drawn blank.
    private fun receipt(
        id: String,
        vararg rows: Pair<String, String?>,
    ): KompotComponent =
        SurfaceComponent(
            id = id,
            dividers = true,
            spacing = 12,
            children =
                rows.mapNotNull { (label, value) ->
                    value?.let {
                        RowComponent(
                            id = "$id-${label.lowercase().replace(' ', '-')}",
                            spacing = 12,
                            children =
                                listOf(
                                    TextComponent(
                                        id = "$id-${label.lowercase().replace(' ', '-')}-label",
                                        text = label,
                                        style = M3Typography.BodyMedium,
                                        color = M3Colors.OnSurfaceVariant,
                                        modifiers = listOf(KompotModifierNode.Weight(1f)),
                                    ),
                                    TextComponent(
                                        id = "$id-${label.lowercase().replace(' ', '-')}-value",
                                        text = it,
                                        style = M3Typography.TitleSmall,
                                        color = M3Colors.OnSurface,
                                    ),
                                ),
                        )
                    }
                },
        )

    private fun rejected(
        order: OrderView,
        balance: Money?,
    ): List<KompotComponent> =
        buildList {
            // THE SAME SHAPE AS THE PAID SCREEN with the other mark, so the two outcomes read as two
            // answers to one question rather than a receipt and a system message.
            add(IconComponent(id = "purchase-mark", icon = CROSS, tone = MessageTones.ERROR))
            add(
                TextComponent(
                    id = "purchase-headline",
                    text = "Payment failed.",
                    style = M3Typography.HeadlineSmall,
                    color = M3Colors.OnSurface,
                ),
            )
            add(
                TextComponent(
                    id = "purchase-rejected",
                    // Nothing was held, so there is nothing to reverse and nothing to state in money.
                    // Saying so is the difference between this screen and the one above, and every
                    // sentence below keeps it.
                    text = refusalText(order, balance),
                    style = M3Typography.BodyMedium,
                    color = M3Colors.OnSurfaceVariant,
                ),
            )
            add(
                receipt(
                    "purchase-refusal",
                    "Reference" to order.orderId.take(8),
                    "Balance" to balance?.let(MoneyFormat::format),
                ),
            )

            // THE CONTROL THAT MATCHES THE REASON, or none — and the way out weighs itself against
            // whichever it was.
            addAll(controls("purchase-rejected-back", "Back", refusalControl(order)))
        }

    // A way out is not a way forward: somebody short of money needs the top-up screen, and somebody
    // whose plan moved needs the catalogue. Null for the branches with no answer — offering `Top up`
    // to a subscriber whose plan left the catalogue would be a button that changes nothing.
    private fun refusalControl(order: OrderView): KompotComponent? =
        when (order.declineReason) {
            PurchaseRefusals.INSUFFICIENT_FUNDS -> {
                ButtonComponent(
                    id = "purchase-rejected-top-up",
                    text = "Top up",
                    action = NavigateAction(TOP_UP_DEEPLINK),
                    modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
                )
            }

            PurchaseRefusals.NOT_ON_SALE, PurchaseRefusals.PRICE_CHANGED, PurchaseRefusals.NO_SUCH_PLAN -> {
                ButtonComponent(
                    id = "purchase-rejected-plans",
                    text = "See plans",
                    action = NavigateAction(PLANS_DEEPLINK),
                    modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
                )
            }

            // `NO_ACCOUNT` and anything this build does not recognise. Both are states a subscriber
            // cannot act on, so the way out is the whole answer — and it is then drawn as the
            // primary, which it correctly is.
            else -> {
                null
            }
        }

    private fun refusalText(
        order: OrderView,
        balance: Money?,
    ): String =
        when (order.declineReason) {
            // BOTH NUMBERS, because "you do not have enough" is a sentence somebody has to do
            // arithmetic on before they know what to type into the top-up field. The balance is
            // omitted rather than guessed when it could not be read — the same rule the confirmation
            // and the home screen follow about the same number: zero is a fact and "we could not
            // tell" is not.
            PurchaseRefusals.INSUFFICIENT_FUNDS -> {
                balance
                    ?.let {
                        "${MoneyFormat.format(order.payload.price)} is more than the " +
                            "${MoneyFormat.format(it)} on your balance. Nothing was charged."
                    }
                    ?: "Your balance does not cover ${MoneyFormat.format(order.payload.price)}. Nothing was charged."
            }

            PurchaseRefusals.NOT_ON_SALE -> {
                "${order.payload.planTitle} is no longer on sale, so it was not bought. Nothing was charged."
            }

            // NOT "the price went up". It may have gone down, and a subscriber told it rose when it
            // fell is one who does not look again.
            PurchaseRefusals.PRICE_CHANGED -> {
                "The price of ${order.payload.planTitle} changed while this was open, so it was not " +
                    "bought at ${MoneyFormat.format(order.payload.price)}. Nothing was charged."
            }

            PurchaseRefusals.NO_SUCH_PLAN -> {
                "${order.payload.planTitle} is no longer offered, so it was not bought. Nothing was charged."
            }

            // `NO_ACCOUNT`, a refusal recorded by a build that knew more than this one, and the case
            // where nothing was recorded at all. The sentence this screen used to give everybody is
            // right for exactly these: it claims nothing it cannot support.
            else -> {
                "This purchase could not be started, and nothing was charged."
            }
        }

    // THE ONE ACTION A SUBSCRIBER MUST TAKE, and until now the screen offered no way to take it.
    //
    // WHAT CHANGED WITH `B-59`: the facts stopped being a sentence. The whole of it was one banner —
    // "<plan> is ready for <price>. Confirm to complete the purchase" — and section 03 of the canvas
    // draws a small table instead: what, how much, and out of what. That is not decoration. A
    // confirmation exists so the amount and its SOURCE are visible at the moment of agreeing, and the
    // number that matters is the one people skim past in prose.
    //
    // The banner stays, and it carries the half a table cannot: who is being waited for. That is the
    // whole difference from the branch below — "confirming with the provider" is a state to sit
    // through, and this one is a state to act on. A subscriber told to wait would have waited out the
    // deadline.
    private fun awaitingConfirmation(
        order: OrderView,
        balance: Money?,
    ): List<KompotComponent> =
        buildList {
            // THE CANVAS'S CONFIRMATION (`B-114`, block 5): a title, the plan and the price as a
            // two-row table, `Pay from` over the one source there is drawn as a chosen option, the
            // pay button with the amount on it, and the hold sentence UNDER the button rather than as
            // the first thing on the screen. The canvas presents all of this as a sheet over the plan
            // page; that is the client's presentation, and this is the content either way.
            add(
                TextComponent(
                    id = "purchase-confirm-title",
                    text = "Confirm purchase",
                    style = M3Typography.HeadlineSmall,
                    color = M3Colors.OnSurface,
                ),
            )
            add(
                receipt(
                    "purchase-facts",
                    "Plan" to order.payload.planTitle,
                    "Price" to MoneyFormat.format(order.payload.price),
                ),
            )

            // WHERE THE MONEY COMES FROM, which is the one thing a confirmation is for. The canvas
            // draws a choice — balance or a card — and there is no choice to draw: this product has
            // no payment methods (`B-40`), and offering a card it cannot charge would be worse than
            // offering none. So the one source is drawn as the chosen option — a tinted card with the
            // check filled — rather than asked for.
            //
            // "LEFT AFTER THIS" rather than a bare balance, because the number is ALREADY net of the
            // hold: `hold` decrements the account in the same statement it checks it against, so this
            // figure is what remains either way. Printing it as "Balance · $35" beside a price of $15
            // invites the subscriber to subtract twice. Omitted when the balance could not be read,
            // for the reason the home screen gives about the same number.
            balance?.let {
                add(
                    TextComponent(
                        id = "purchase-source-label",
                        text = "Pay from",
                        style = M3Typography.LabelMedium,
                        color = M3Colors.OnSurfaceVariant,
                    ),
                )
                add(
                    SurfaceComponent(
                        id = "purchase-source",
                        tone = SurfaceTones.ACCENT,
                        children =
                            listOf(
                                RowComponent(
                                    id = "purchase-source-row",
                                    spacing = 12,
                                    children =
                                        listOf(
                                            IconComponent(
                                                id = "purchase-source-mark",
                                                icon = CHECK,
                                                tone = MessageTones.INFO,
                                                size = 28,
                                            ),
                                            ColumnComponent(
                                                id = "purchase-source-text",
                                                spacing = 2,
                                                children =
                                                    listOf(
                                                        TextComponent(
                                                            id = "purchase-source-title",
                                                            text = "Balance — ${MoneyFormat.format(
                                                                it,
                                                            )} left after this",
                                                            style = M3Typography.TitleSmall,
                                                            color = M3Colors.OnPrimaryContainer,
                                                        ),
                                                        TextComponent(
                                                            id = "purchase-source-note",
                                                            text = "Instant, no fee",
                                                            style = M3Typography.BodySmall,
                                                            color = M3Colors.OnPrimaryContainer,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                )
            }

            add(
                ButtonComponent(
                    id = "purchase-confirm",
                    // THE AMOUNT ON THE BUTTON, like the plan detail's `Buy for $X`. A subscriber
                    // who reads only the control they are about to press still reads the price.
                    text = "Pay ${MoneyFormat.format(order.payload.price)}",
                    action = ConfirmPurchaseAction(order.orderId),
                    modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
                ),
            )

            // "HELD" AND NOT "NOTHING HAS HAPPENED", and the first live run of this screen is what
            // forced the distinction: the sentence said "nothing has been charged yet" beside a
            // balance that had ALREADY dropped by the price. Both were true and together they read as
            // a contradiction. It sits under the button now — the fact is worth keeping, and it is
            // not the first thing a subscriber came here to read.
            add(
                TextComponent(
                    id = "purchase-awaiting",
                    text =
                        "${MoneyFormat.format(order.payload.price)} is on hold and has not been charged. " +
                            "Let the window pass and it is released.",
                    style = M3Typography.BodySmall,
                    color = M3Colors.OnSurfaceVariant,
                ),
            )

            // LEAVING WITHOUT CONFIRMING IS A PATH, not an escape hatch. The order keeps its
            // deadline and rolls itself back when it passes — which is the compensated branch this
            // product exists to demonstrate, reached the way a subscriber would actually reach it.
            // A text link, not a pill the size of `Pay`.
            add(
                ButtonComponent(
                    id = "purchase-not-now",
                    text = "Not now",
                    action = NavigateAction(HOME_DEEPLINK),
                    variant = ButtonEmphasis.LINK,
                    modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
                ),
            )
        }

    // ONE FACT, AS A ROW: the label on the left and the value on the right, which is how the canvas
    // draws every one of them and how a column of them becomes something to read down rather than
    // through. The same shape the plan detail screen uses for "What is included".
    private fun fact(
        id: String,
        label: String,
        value: String,
    ): KompotComponent =
        RowComponent(
            id = id,
            spacing = 8,
            children =
                listOf(
                    TextComponent(
                        id = "$id-label",
                        text = label,
                        style = M3Typography.BodyMedium,
                        color = M3Colors.OnSurfaceVariant,
                    ),
                    TextComponent(
                        id = "$id-value",
                        text = value,
                        style = M3Typography.BodyMedium,
                        color = M3Colors.OnSurface,
                    ),
                ),
        )

    private fun inFlight(order: OrderView): List<KompotComponent> =
        listOf(
            BannerComponent(
                id = "purchase-in-flight",
                text = "Confirming with the payment provider. Keep the app open — this usually takes under 15 seconds.",
                tone = MessageTones.INFO,
            ),
            *controls("purchase-in-flight-back", "Back").toTypedArray(),
        )
}

// The canvas's outcome marks, on the 24-grid every icon in this build is drawn on.
private val CHECK = VectorIcon(paths = listOf("M5 12l5 5L20 7"))
private val CROSS = VectorIcon(paths = listOf("M6 6l12 12M18 6L6 18"))
