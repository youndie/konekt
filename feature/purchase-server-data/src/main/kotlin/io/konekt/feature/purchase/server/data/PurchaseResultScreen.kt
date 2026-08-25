package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.OrderView
import io.konekt.feature.purchase.server.domain.Reversal
import io.konekt.money.DayFormat
import io.konekt.money.MoneyFormat

// The screen a subscriber lands on after a purchase ends, built on the server as a component tree.
//
// The rollback branch is the one this exists for, and the canvas is specific about why: a rollback is
// stated IN MONEY — what was reversed, what the balance is now, and the reference to quote — rather
// than as an apology. A subscriber who can reconcile a reversal against their bank does not ring
// support; one who is told "something went wrong" does.
object PurchaseResultScreen {
    fun build(
        order: OrderView,
        reversal: Reversal?,
        balance: Money?,
    ): KompotComponent =
        ColumnComponent(
            id = "purchase-result",
            spacing = 16,
            children =
                when (order.status) {
                    OrderStatus.COMPENSATED -> reversed(order, reversal, balance)
                    OrderStatus.COMPLETED -> completed(order)
                    OrderStatus.REJECTED -> rejected(order)
                    else -> inFlight(order)
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
        }

    private fun completed(order: OrderView): List<KompotComponent> =
        listOf(
            BannerComponent(
                id = "purchase-completed",
                text = "${order.payload.planTitle} is active.",
                tone = MessageTones.INFO,
            ),
            OrderRowComponent(
                id = "order-${order.orderId}",
                reference = order.orderId.take(8),
                title = order.payload.planTitle,
                dateText = "",
                amountText = MoneyFormat.format(order.payload.price),
                status = OrderStatuses.COMPLETED,
                statusText = "Paid",
            ),
        )

    private fun rejected(order: OrderView): List<KompotComponent> =
        listOf(
            BannerComponent(
                id = "purchase-rejected",
                // Nothing was held, so there is nothing to reverse and nothing to state in money.
                // Saying so is the difference between this screen and the one above.
                text = "This purchase could not be started, and nothing was charged.",
                tone = MessageTones.ERROR,
            ),
        )

    private fun inFlight(order: OrderView): List<KompotComponent> =
        listOf(
            BannerComponent(
                id = "purchase-in-flight",
                text = "Confirming with the payment provider. Keep the app open — this usually takes under 15 seconds.",
                tone = MessageTones.INFO,
            ),
        )
}
