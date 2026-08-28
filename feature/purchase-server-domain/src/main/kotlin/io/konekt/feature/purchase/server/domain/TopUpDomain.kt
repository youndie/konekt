package io.konekt.feature.purchase.server.domain

import io.konekt.domain.Currency
import io.konekt.domain.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.workinprogress.petich.PetichPayload

// What a top-up is, on the saga. @SerialName for the same load-bearing reason as PurchasePayload's:
// without it the polymorphic discriminator is the fully qualified class name, and the STORAGE format
// of every persisted saga then depends on where this package lives.
@Serializable
@SerialName("top_up")
data class TopUpPayload(
    val subscriberId: String,
    val accountId: String,
    val amount: Money,
) : PetichPayload()

const val TOP_UP_SAGA_TYPE = "top_up"

// THE BOUNDS, and they are the product's rather than the engine's.
//
// A minimum because a provider that charges per transaction turns a one-cent top-up into a loss, and
// because a zero or negative amount is not a small top-up but a withdrawal wearing its name — the
// ledger would happily take it. A maximum because this is a prepaid mobile account and not a deposit
// account: a subscriber who means 500 and types 50000 is a support call either way, and refusing is
// the cheaper half of it.
//
// Stated here rather than as a database constraint so that the refusal reaches the subscriber as a
// sentence. A CHECK constraint answers the same question with a 500.
object TopUpLimits {
    const val MIN_MINOR = 1_000L
    const val MAX_MINOR = 5_000_000L
}

// AN AMOUNT PLUS THE UNIT IT IS IN, because a `Long` does not say and two callers read it differently.
//
// The DTO endpoint takes minor units, which is the domain's own unit and right for a machine. The
// top-up FORM takes what a person typed: kompot's amount input filters to digits and hands back the
// integer it displays, so a subscriber who sees "50" in a field labelled `$` has sent 50 — of dollars.
// Both arrived at `Params(subscriberId, amountMinor: Long)` and one of them was wrong by a factor of a
// hundred: typing 5000 credited $50, and typing 50 was refused by a screen that had just said the
// minimum was $10 (`B-67`). Nothing objected, because both sides were a `Long`.
//
// THE CONVERSION HAPPENS WHERE THE CURRENCY IS KNOWN, which is why this is a type and not a
// multiplication at the edge. The exponent belongs to the currency and the currency belongs to the
// ACCOUNT — deliberately, so that a request naming another one is unrepresentable rather than
// validated. A route that multiplied by a hundred itself would be reintroducing the assumption the
// use case exists to avoid, and it would be right only for as long as this product has one currency.
sealed interface TopUpAmount {
    fun toMoney(currency: Currency): Money

    // What a person typed, in whole units of the currency on the screen.
    data class Whole(
        val units: Long,
    ) : TopUpAmount {
        override fun toMoney(currency: Currency): Money = Money.ofMajor(units, currency)
    }

    // The domain's own unit, for a caller that already speaks it.
    data class Minor(
        val units: Long,
    ) : TopUpAmount {
        override fun toMoney(currency: Currency): Money = Money(units, currency)
    }

    companion object {
        fun whole(units: Long): TopUpAmount = Whole(units)

        fun minor(units: Long): TopUpAmount = Minor(units)
    }
}

// The top-up as a subscriber sees it, derived from the saga's status the same way an order is.
//
// It has one fewer state than an order: there is no AWAITING_CONFIRMATION, because this saga never
// suspends. A purchase waits because the subscriber is agreeing to spend money they already have; a
// top-up is the agreement — they pressed the button and the provider either takes the money or does
// not. Adding a confirmation step would cost two more saga rows per top-up (petich writes at every
// step boundary) to ask a question that was already answered.
data class TopUpView(
    val id: String,
    val status: OrderStatus,
    val amount: Money,
    val balance: Money,
    val declineReason: String? = null,
)
