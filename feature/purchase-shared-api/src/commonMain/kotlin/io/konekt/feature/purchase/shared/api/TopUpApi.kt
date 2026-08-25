package io.konekt.feature.purchase.shared.api

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// PUTTING MONEY IN, which until B-40 nothing could do: an account is created with zero and no route,
// saga or consumer ever raised it, so the first purchase any real subscriber attempted was refused
// for insufficient funds.
//
// Its own resource tree rather than a verb on `Purchases`, because the two are opposite directions
// of the same ledger and a client that can spend is not necessarily a client that can top up.
@Resource("/api/v1/top-ups")
class TopUps {
    @Resource("{topUpId}")
    class ById(
        val parent: TopUps = TopUps(),
        val topUpId: String,
    )
}

// The amount in MINOR UNITS and no currency, which is two decisions rather than a shortcut.
//
// Minor units because a decimal on the wire is how a top-up of 10.00 becomes 9.99 somewhere between
// two languages; `Money` is minor units everywhere else in this build for the same reason (B-31).
// No currency because the account has one and a request that names a different one is a question
// this product has no answer to — a subscriber cannot hold two balances.
@Serializable
data class CreateTopUpRequest(
    val amountMinor: Long,
)

// What a top-up looks like to a subscriber. Deliberately the same shape as PurchaseOrderResponse
// where it can be: `status` is the saga's phase in the product's words, and `declineReason` is what
// makes a refusal something to act on rather than something to ring support about.
@Serializable
data class TopUpResponse(
    val topUpId: String,
    val status: String,
    // What was asked for and what the balance is NOW, both formatted by the server — the client
    // renders text and cannot format money inconsistently (D15).
    val amountText: String,
    val balanceText: String,
    // Set on the refused branch. The balance is unchanged in that case, and this is the sentence
    // that says why.
    val declineReason: String? = null,
)
