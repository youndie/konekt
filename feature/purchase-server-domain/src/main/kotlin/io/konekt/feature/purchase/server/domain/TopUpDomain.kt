package io.konekt.feature.purchase.server.domain

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
