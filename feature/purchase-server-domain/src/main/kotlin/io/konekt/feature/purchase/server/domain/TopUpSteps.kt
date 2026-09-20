package io.konekt.feature.purchase.server.domain

import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.PetichAnnouncement
import io.github.youndie.petich.PetichAnnouncementContext
import io.github.youndie.petich.PetichCheck
import io.github.youndie.petich.PetichCheckContext
import io.github.youndie.petich.PetichDefinition
import io.github.youndie.petich.PetichStep
import io.github.youndie.petich.PetichStepContext
import io.github.youndie.petich.PetichStepRecord
import io.github.youndie.petich.petich
import io.github.youndie.petich.recorded
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// THREE MEMBERS, and the order is the whole design — now written where it can be read, at the
// bottom of this file, rather than reconstructed from three `phase` fields and three priorities.
//
// A top-up is the purchase saga pointed the other way, and pointing it the other way changes which
// member may not be reordered: the provider settles BEFORE the balance moves. A balance raised
// before the provider confirmed is money the operator has given away, and it is given away in
// exactly the case the mock exists to produce — a decline. The purchase saga can afford to hold
// funds first because the money it moves is already the subscriber's.
//
// There is no confirmation member and so no suspension. A purchase waits because the subscriber is
// agreeing to spend money they already have; a top-up IS the agreement. See TopUpView.

// What the collecting member did, so that its own undo can tell that it did it.
//
// THIS REPLACES A LOOKUP INTO OUR OWN LEDGER. `AccountBalances.debit` used to ask whether a `TOP_UP`
// entry existed before reversing anything, because a compensation could not otherwise tell a credit
// that happened from one that never did — petich compensates the member whose outcome it never
// learned, and `settle` throwing is exactly that. The engine now carries the evidence beside the
// member that wrote it, so the question is asked where it arises.
@Serializable
@SerialName("topup_credited")
data class Credited(
    val minorUnits: Long,
) : PetichStepRecord()

// 1. VALIDATION — what can refuse before anything has happened. No undo to write: it does nothing.
class ValidateTopUp(
    private val balances: AccountBalances,
) : PetichCheck<TopUpPayload> {
    override suspend fun check(
        ctx: PetichCheckContext,
        payload: TopUpPayload,
    ) {
        val minor = payload.amount.minorUnits
        if (minor < TopUpLimits.MIN_MINOR) {
            return ctx.reject("that is below the smallest top-up we can take")
        }
        if (minor > TopUpLimits.MAX_MINOR) {
            return ctx.reject("that is more than we can take in one top-up")
        }

        // Re-read rather than trusted from the request: the account id on the payload was resolved
        // when the saga was created, and a saga can be processed later than it was written.
        balances.findAccountOf(payload.subscriberId) ?: return ctx.reject("no account")
    }
}

// 2. EXECUTION — take the money from the provider, and only then raise the balance.
class CollectFunds(
    private val balances: AccountBalances,
    private val payments: PaymentGateway,
) : PetichStep<TopUpPayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: TopUpPayload,
    ) {
        val settlement = payments.settle(ctx.petich.id, payload.amount)
        if (settlement is PaymentGateway.Settlement.Declined) {
            // Recorded HERE, by the member that learned it, exactly as the purchase saga does:
            // petich carries a reason to its metrics and does not persist one.
            balances.recordDecline(payload.accountId, ctx.petich.id, payload.amount, settlement.reason)
            return ctx.fail(settlement.reason)
        }

        balances.credit(payload.accountId, ctx.petich.id, payload.amount)
        // The evidence, written in the same breath as the effect and committed with it.
        ctx.record(Credited(payload.amount.minorUnits))
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: TopUpPayload,
    ) {
        // NOTHING TO TAKE BACK IF NOTHING WAS GIVEN, and this is now one line rather than a lookup
        // into the ledger. petich calls this for the member whose outcome it never learned — a
        // `settle` that threw, a gateway timeout — and without the record that arrives as a
        // reversal against a top-up with no credit, taking a balance down by an amount nobody added
        // (youndie/konekt#48).
        ctx.recorded<Credited>() ?: return

        balances.debit(payload.accountId, ctx.petich.id, payload.amount)
    }
}

// 3. POST_PROCESSING — say what happened, in the same write as the state change.
class AnnounceTopUp(
    private val events: TopUpEvents,
) : PetichAnnouncement<TopUpPayload> {
    override suspend fun announce(
        ctx: PetichAnnouncementContext,
        payload: TopUpPayload,
    ) {
        ctx.emit(events.completed(ctx.petich.id, payload))
    }
}

class TopUpEvents(
    private val json: Json,
) {
    fun completed(
        topUpId: String,
        payload: TopUpPayload,
    ): OutboxEvent =
        // The id is the top-up plus the kind rather than a random one, so the same completion cannot
        // be announced twice: delivery is at-least-once, and a consumer keyed on this id can tell a
        // redelivery from a second top-up.
        TopUpEvent(
            id = "$topUpId:topup.completed",
            type = "topup.completed",
            payload =
                json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf(
                            "topUpId" to JsonPrimitive(topUpId),
                            "subscriberId" to JsonPrimitive(payload.subscriberId),
                            "amountMinor" to JsonPrimitive(payload.amount.minorUnits),
                            "currency" to JsonPrimitive(payload.amount.currency.name),
                        ),
                    ),
                ),
        )

    // OutboxEvent is an interface — petich carries the intent, not a class of ours — so the concrete
    // shape is the application's.
    private data class TopUpEvent(
        override val id: String,
        override val type: String,
        override val payload: String,
    ) : OutboxEvent
}

// The top-up saga, in the order it runs.
//
// The engine keeps definitions by type, so the note this replaced — that an engine built with the
// purchase list would run the wrong compensations for a top-up, or none — no longer describes
// anything that can happen.
fun topUpPetich(
    balances: AccountBalances,
    payments: PaymentGateway,
    json: Json,
): PetichDefinition<TopUpPayload> =
    // The TYPE COMES FROM THE CONSTANT the rest of the code already uses. Spelled by hand it read
    // `topup` against a row that says `top_up`, and petich — before youndie/petich#78 — ran zero
    // members and reported the saga complete.
    petich(TOP_UP_SAGA_TYPE) {
        validate("limits", ValidateTopUp(balances))
        step("collect-funds", CollectFunds(balances, payments))
        announce("announce", AnnounceTopUp(TopUpEvents(json)))
    }
