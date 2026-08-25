package io.konekt.feature.purchase.server.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.petich.InterceptorResult
import ru.workinprogress.petich.OutboxEvent
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichInterceptor
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase

// THREE INTERCEPTORS, and the order is the whole design.
//
// A top-up is the purchase saga pointed the other way, and pointing it the other way changes which
// step may not be reordered: the provider settles BEFORE the balance moves. A balance raised before
// the provider confirmed is money the operator has given away, and it is given away in exactly the
// case the mock exists to produce — a decline. The purchase saga can afford to hold funds first
// because the money it moves is already the subscriber's.
//
// There is no confirmation step and so no Suspend. A purchase waits because the subscriber is
// agreeing to spend money they already have; a top-up IS the agreement. See TopUpView.

// 1. VALIDATION — what can refuse before anything has happened.
class ValidateTopUpInterceptor(
    private val balances: AccountBalances,
) : PetichInterceptor<TopUpPayload> {
    override val phase = PetichPhase.VALIDATION

    override fun supports(payload: PetichPayload) = payload is TopUpPayload

    override suspend fun intercept(
        petich: Petich,
        payload: TopUpPayload,
    ): InterceptorResult {
        val minor = payload.amount.minorUnits
        if (minor < TopUpLimits.MIN_MINOR) {
            return InterceptorResult.Reject("that is below the smallest top-up we can take")
        }
        if (minor > TopUpLimits.MAX_MINOR) {
            return InterceptorResult.Reject("that is more than we can take in one top-up")
        }

        // Re-read rather than trusted from the request: the account id on the payload was resolved
        // when the saga was created, and a saga can be processed later than it was written.
        balances.findAccountOf(payload.subscriberId) ?: return InterceptorResult.Reject("no account")

        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: TopUpPayload,
    ) = Unit
}

// 2. EXECUTION — take the money from the provider, and only then raise the balance.
class CollectFundsInterceptor(
    private val balances: AccountBalances,
    private val payments: PaymentGateway,
) : PetichInterceptor<TopUpPayload> {
    override val phase = PetichPhase.EXECUTION

    override fun supports(payload: PetichPayload) = payload is TopUpPayload

    override suspend fun intercept(
        petich: Petich,
        payload: TopUpPayload,
    ): InterceptorResult {
        val settlement = payments.settle(petich.id, payload.amount)
        if (settlement is PaymentGateway.Settlement.Declined) {
            // Recorded HERE, by the step that learned it, exactly as the purchase saga does: petich
            // carries a Compensate reason to its metrics and does not persist one.
            balances.recordDecline(payload.accountId, petich.id, payload.amount, settlement.reason)
            return InterceptorResult.Compensate(settlement.reason)
        }

        balances.credit(payload.accountId, petich.id, payload.amount)
        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: TopUpPayload,
    ) {
        // ONLY REACHED WHEN THE CREDIT ACTUALLY HAPPENED. A decline returns Compensate from the step
        // itself, and petich walks back through the steps that ran FORWARD — this one did not, so
        // this does not run and the balance is untouched. That is the AC of B-40 stated as a
        // mechanism rather than as a test: a refused top-up leaves the balance exactly where it was.
        //
        // What it does run for is a failure AFTER the money landed, where the subscriber is holding
        // money the operator was not paid for.
        balances.debit(payload.accountId, petich.id, payload.amount)
    }
}

// 3. POST_PROCESSING — say what happened, in the same transaction as the state change.
class AnnounceTopUpInterceptor(
    private val events: TopUpEvents,
) : PetichInterceptor<TopUpPayload> {
    override val phase = PetichPhase.POST_PROCESSING

    override fun supports(payload: PetichPayload) = payload is TopUpPayload

    override suspend fun intercept(
        petich: Petich,
        payload: TopUpPayload,
    ): InterceptorResult =
        InterceptorResult.Proceed(
            outboxEvents = listOf(events.completed(petich.id, payload)),
        )

    override suspend fun compensate(
        petich: Petich,
        payload: TopUpPayload,
    ) = Unit
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

// The interceptor list of the top-up saga, in one place, because an engine built with the purchase
// list would run the wrong compensations for a top-up — or none.
fun topUpInterceptors(
    balances: AccountBalances,
    payments: PaymentGateway,
    json: Json,
): List<PetichInterceptor<*>> =
    listOf(
        ValidateTopUpInterceptor(balances),
        CollectFundsInterceptor(balances, payments),
        AnnounceTopUpInterceptor(TopUpEvents(json)),
    )
