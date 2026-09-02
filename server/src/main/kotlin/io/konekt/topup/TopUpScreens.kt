package io.konekt.topup

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.SizeType
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.form.standard.AmountFieldDefinition
import io.github.youndie.kompot.form.standard.RequiredRule
import io.github.youndie.kompot.forms.AmountInputComponent
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.forms.SubmitFormAction
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.TopUpLimits
import io.konekt.feature.purchase.server.domain.TopUpView
import io.konekt.feature.purchase.shared.api.TopUpForms
import io.konekt.feature.shell.shared.api.HOME_DEEPLINK
import io.konekt.money.MoneyFormat

// PUTTING MONEY IN, AS A SCREEN. Everything under it existed and none of it could be reached.
//
// The saga, the limits, the compensation and the routes landed in `B-40`; what nothing built was the
// one screen that chooses an amount, so a subscriber's balance could only ever go down. This is the
// same shape the confirm button was in — a server half that worked and a product with no way to press
// it — and the same shape again as the eSIM wizard, which is still in it.
object TopUpScreens {
    private val FILLS_THE_ROW = listOf(KompotModifierNode.Size(width = SizeType.Fill))

    // THE RANGE IS NOT A CLIENT RULE, and that is a finding rather than an omission.
    //
    // `form-standard` carries `RequiredRule`, `MaxAmountRule` and no minimum at all — and its maximum
    // compares against a BALANCE field, which is the "do not spend more than you have" rule and the
    // opposite direction from this one. So the only thing the client can refuse without a round trip
    // is an empty field; `TopUpLimits` stays where it is enforced, in the use case, and a refusal
    // comes back as a screen.
    //
    // Saying the range in the label is therefore not decoration: it is the only place a subscriber
    // learns it before being refused.
    fun amount(error: String? = null): KompotFormResponse =
        KompotFormResponse(
            schema =
                FormSchema(
                    formId = TopUpForms.AMOUNT_FORM,
                    fields =
                        listOf(
                            AmountFieldDefinition(
                                fieldId = TopUpForms.FIELD_AMOUNT,
                                rules = listOf(RequiredRule(errorMessage = "Enter an amount")),
                            ),
                        ),
                ),
            screen =
                ColumnComponent(
                    id = "top-up",
                    spacing = 16,
                    children =
                        buildList {
                            add(
                                TextComponent(
                                    id = "top-up-title",
                                    text = "Top up",
                                    style = M3Typography.HeadlineSmall,
                                    color = M3Colors.OnSurface,
                                ),
                            )
                            error?.let {
                                add(
                                    BannerComponent(
                                        id = "top-up-error",
                                        text = it,
                                        tone = MessageTones.ERROR,
                                    ),
                                )
                            }
                            add(
                                amountField(Currency.DEFAULT),
                            )
                            add(
                                TextComponent(
                                    id = "top-up-limits",
                                    text = limitsLine(),
                                    style = M3Typography.BodySmall,
                                    color = M3Colors.OnSurfaceVariant,
                                ),
                            )
                            add(
                                ButtonComponent(
                                    id = "top-up-submit",
                                    text = "Top up",
                                    action = SubmitFormAction(TopUpForms.AMOUNT_FORM),
                                    modifiers = FILLS_THE_ROW,
                                ),
                            )
                        },
                ),
        )

    // The result, and it has three states rather than the order screen's six: this saga never
    // suspends, so there is no confirmation to wait for. `when` with no `else`, for the reason the
    // file next door gives at length — a state added to the enum must be a compile error here.
    fun result(view: TopUpView): KompotComponent =
        ColumnComponent(
            id = "top-up-result",
            spacing = 16,
            children =
                when (view.status) {
                    OrderStatus.COMPLETED -> completed(view)

                    // TWO REFUSALS, AND THEY MUST NOT SHARE A SENTENCE. Both were `refused(view)`
                    // for one build, and the first live run below the minimum answered "The provider
                    // did not take the payment" — about a provider that was never asked. The
                    // validation phase rejects before execution begins, so nothing had been
                    // attempted and no card was touched.
                    //
                    // The wrong half is the one a subscriber acts on: told their payment failed, they
                    // check their bank; told the amount is outside the range, they type another one.
                    OrderStatus.REJECTED -> notAccepted(view)

                    // The provider WAS asked here, and said no — or something after it failed and the
                    // saga walked itself back. Stated in money, because a subscriber who has just
                    // been told a payment failed has exactly one question and it is whether the money
                    // left their card.
                    OrderStatus.COMPENSATED -> refused(view)

                    // Still running, or already walking itself back. Neither is anything a
                    // subscriber can act on, and both are honest about the balance being the one
                    // from before.
                    OrderStatus.PENDING, OrderStatus.COMPENSATING -> inFlight(view)

                    // A top-up never waits for the subscriber — pressing the button IS the
                    // agreement — so this state cannot arise. It is drawn rather than ignored,
                    // because a screen that draws nothing is one that failed to load.
                    OrderStatus.AWAITING_CONFIRMATION -> inFlight(view)
                },
        )

    private fun completed(view: TopUpView): List<KompotComponent> =
        listOf(
            TextComponent(
                id = "top-up-result-title",
                text = "${view.amountText()} added",
                style = M3Typography.HeadlineSmall,
                color = M3Colors.OnSurface,
            ),
            balanceLine(view),
            wayOut(),
        )

    // REFUSED BEFORE ANYTHING WAS ATTEMPTED. `declineReason` is deliberately not consulted: it is
    // read out of a DECLINE ledger entry, and a validation reject writes none — so it is null here by
    // construction rather than by accident, and a fallback naming the provider would be a sentence
    // about something that did not happen.
    //
    // The range is repeated instead, because it is the one thing the subscriber can act on and the
    // form they came from is the place they will act.
    private fun notAccepted(view: TopUpView): List<KompotComponent> =
        listOf(
            TextComponent(
                id = "top-up-result-title",
                text = "That amount was not accepted",
                style = M3Typography.HeadlineSmall,
                color = M3Colors.OnSurface,
            ),
            BannerComponent(
                id = "top-up-result-reason",
                text = "Nothing was charged. ${limitsLine()}",
                tone = MessageTones.ERROR,
            ),
            TextComponent(
                id = "top-up-result-balance",
                text = "Your balance is unchanged: ${view.balanceText()}.",
                style = M3Typography.BodyMedium,
                color = M3Colors.OnSurfaceVariant,
            ),
            wayOut(),
        )

    private fun refused(view: TopUpView): List<KompotComponent> =
        listOf(
            TextComponent(
                id = "top-up-result-title",
                text = "That payment did not go through",
                style = M3Typography.HeadlineSmall,
                color = M3Colors.OnSurface,
            ),
            BannerComponent(
                id = "top-up-result-reason",
                // The provider's own sentence when there is one. `declineReason` carries a code a
                // subscriber can quote to their bank, which is the difference between a refusal they
                // can act on and one they can only ring support about.
                text = view.declineReason ?: "The provider did not take the payment.",
                tone = MessageTones.ERROR,
            ),
            TextComponent(
                id = "top-up-result-balance",
                text = "Your balance is unchanged: ${view.balanceText()}.",
                style = M3Typography.BodyMedium,
                color = M3Colors.OnSurfaceVariant,
            ),
            wayOut(),
        )

    private fun inFlight(view: TopUpView): List<KompotComponent> =
        listOf(
            TextComponent(
                id = "top-up-result-title",
                text = "Still going through",
                style = M3Typography.HeadlineSmall,
                color = M3Colors.OnSurface,
            ),
            balanceLine(view),
            wayOut(),
        )

    private fun balanceLine(view: TopUpView) =
        TextComponent(
            id = "top-up-result-balance",
            text = "Balance: ${view.balanceText()}",
            style = M3Typography.BodyMedium,
            color = M3Colors.OnSurfaceVariant,
        )

    // EVERY STATE HAS ONE. A screen that is not a tab and offers nothing to press is a screen a
    // subscriber is stuck on — which is exactly what the purchase result was until somebody opened it
    // in the running application.
    private fun wayOut() =
        ButtonComponent(
            id = "top-up-result-done",
            text = "Done",
            action = NavigateAction(HOME_DEEPLINK),
            modifiers = FILLS_THE_ROW,
        )

    private fun TopUpView.amountText() = MoneyFormat.format(amount)

    private fun TopUpView.balanceText() = MoneyFormat.format(balance)

    // THE FIELD, and how the currency symbol sits on it — which symbol, which side, and whether it
    // stands off the number — asked of the table every other amount in this product is written
    // from, never spelled here.
    //
    // `amount_input` took only a `currencySuffix` until `0.33.1.93` (kompot#97), and filling it
    // whatever the currency is what drew "50 $" six lines above this screen's own "Between $10 and
    // $50,000": one screen writing one currency two ways, in one response (`B-70`). The workaround
    // put the symbol in the LABEL for a symbol-first currency; it is deleted, because the field now
    // takes both sides and at most one is set. The gap came last — the field always drew `$ 50`
    // until `0.34.0.97` (kompot#99) — and it is the one fact that had no workaround, because it
    // belongs to the field's own layout and appears in no tree. `currencySpaced` defaults to `true`
    // upstream for the sake of every payload written before it existed, so it is set explicitly
    // here for every currency rather than only for the ones that want no gap.
    internal fun amountField(currency: Currency): AmountInputComponent =
        AmountInputComponent(
            id = "top-up-amount",
            fieldId = TopUpForms.FIELD_AMOUNT,
            label = "Amount",
            currencyPrefix = MoneyFormat.leadingSymbol(currency),
            currencySuffix = MoneyFormat.trailingSymbol(currency),
            currencySpaced = MoneyFormat.symbolSpaced(currency),
        )

    private fun limitsLine(): String {
        val currency = Currency.DEFAULT
        val min = MoneyFormat.format(Money(TopUpLimits.MIN_MINOR, currency))
        val max = MoneyFormat.format(Money(TopUpLimits.MAX_MINOR, currency))
        return "Between $min and $max."
    }
}
