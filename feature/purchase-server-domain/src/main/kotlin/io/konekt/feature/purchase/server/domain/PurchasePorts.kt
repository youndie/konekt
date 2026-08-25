package io.konekt.feature.purchase.server.domain

import io.konekt.domain.Money
import kotlin.time.Instant

// The catalogue. In-memory for now: the BSS is outside this system's boundary, and a real catalogue
// with prices that move is B-19's business. What a purchase needs from it is two questions.
interface PlanCatalog {
    suspend fun find(planId: String): Plan?
}

// Money moving on one account, as three operations rather than a setter.
//
// A HOLD DEBITS THE VISIBLE BALANCE, and that is a decision with a cost. The subscriber sees the
// money gone the moment they start a purchase, and gets it back if they never confirm — which is
// exactly what the canvas draws ("your balance is back to where it was"). The alternative is a
// separate held amount and an available-versus-total distinction, which is more honest and is a
// second number on every screen that shows a balance. This product has one number.
interface AccountBalances {
    suspend fun findAccountOf(subscriberId: String): AccountSnapshot?

    // Refuses rather than going negative, and refuses in the database rather than after a read: two
    // purchases started together both pass a read-then-check, and what they would overspend is real
    // money.
    suspend fun hold(
        accountId: String,
        orderId: String,
        amount: Money,
    ): Boolean

    suspend fun release(
        accountId: String,
        orderId: String,
        amount: Money,
    )

    // The hold becomes a spend. No balance movement — the money left at hold time — so this is the
    // ledger entry that turns a reservation into a purchase.
    suspend fun capture(
        accountId: String,
        orderId: String,
        amount: Money,
    )

    // A zero-sum ledger entry that exists to carry a sentence: why the provider refused. The screen
    // that states a rollback in money reads this, and without it the only honest wording is "the
    // operation did not go through", which is what a subscriber rings support about.
    suspend fun recordDecline(
        accountId: String,
        orderId: String,
        amount: Money,
        reason: String,
    )

    suspend fun declineReason(orderId: String): String?

    // What was actually returned, and when. Read from the ledger rather than recomputed from the
    // order's price: they agree today, and the ledger is the record of what happened while the price
    // is the record of what was asked for. When a partial reversal exists one day, only one of the
    // two will still be right.
    suspend fun reversalOf(orderId: String): Reversal?

    suspend fun balanceOf(accountId: String): Money?
}

data class Reversal(
    val amount: Money,
    val at: Instant,
)

data class AccountSnapshot(
    val id: String,
    val balance: Money,
)

interface Entitlements {
    suspend fun createPending(
        orderId: String,
        subscriberId: String,
        planId: String,
        price: Money,
    )

    suspend fun activate(orderId: String)

    suspend fun cancel(orderId: String)

    suspend fun findByOrder(orderId: String): Entitlement?
}
