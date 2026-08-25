package io.konekt.feature.purchase.server.domain

import io.konekt.domain.Money

// The payment provider, which this system does not have.
//
// It is outside the boundary — no card is ever touched, nothing leaves the process — and it is
// modelled anyway, because the branch it produces is the one the whole saga exists to show. A
// provider that always succeeds can demonstrate three of the canvas's four purchase frames; the
// fourth is a decline, and without a way to cause one on demand it cannot be reached at all.
interface PaymentGateway {
    suspend fun settle(
        orderId: String,
        amount: Money,
    ): Settlement

    sealed interface Settlement {
        data object Approved : Settlement

        // The reason is carried because the rollback screen states it. "The provider declined the
        // operation" is what a subscriber can act on; "something went wrong" is what they ring
        // support about.
        data class Declined(
            val reason: String,
        ) : Settlement
    }
}
