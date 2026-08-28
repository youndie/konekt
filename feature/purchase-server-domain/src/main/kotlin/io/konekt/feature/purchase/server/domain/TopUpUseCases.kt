package io.konekt.feature.purchase.server.domain

import io.konekt.domain.KonektException
import io.konekt.domain.Money
import io.konekt.domain.suspendRunCatching
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.SimpleEnrichedPayload
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StartTopUpUseCase(
    // The TOP-UP engine, and the qualifier matters. An engine built with the purchase interceptor
    // list would find no step that supports a TopUpPayload, complete a saga that did nothing, and
    // answer COMPLETED for a top-up that never took a penny.
    private val engine: PetichEngine,
    private val topUps: PetichRepository,
    private val balances: AccountBalances,
) {
    suspend operator fun invoke(params: Params): Result<TopUpView> =
        suspendRunCatching {
            val account =
                balances.findAccountOf(params.subscriberId)
                    ?: throw KonektException.NotFound("account")

            // The currency is the ACCOUNT'S, never the request's. A subscriber holds one balance, so
            // a request naming another currency is a question this product has no answer to — and
            // taking it from the account is what makes that unrepresentable rather than validated.
            //
            // And the UNIT comes from the caller rather than being assumed here. It used to be
            // assumed — the parameter was `amountMinor` and the form route handed it whole units —
            // which is `B-67`, a hundredfold error that no type objected to because both sides were a
            // `Long`. `TopUpAmount` converts at this line, where the currency's exponent is known.
            val amount = params.amount.toMoney(account.balance.currency)

            val topUpId = Uuid.random().toString()

            engine.process(
                Petich(
                    id = topUpId,
                    type = TOP_UP_SAGA_TYPE,
                    status = PetichStatus.DRAFT,
                    payload =
                        TopUpPayload(
                            subscriberId = params.subscriberId,
                            accountId = account.id,
                            amount = amount,
                        ),
                    enrichedPayload = SimpleEnrichedPayload(),
                ),
            )

            viewOf(topUpId, account.id)
        }

    private suspend fun viewOf(
        topUpId: String,
        accountId: String,
    ): TopUpView {
        // Read back rather than inferred from the engine's answer: that answer says what happened on
        // this pass, and what the client needs is where the top-up now stands.
        val saga = topUps.findById(topUpId) ?: throw KonektException.NotFound("top-up")
        val payload = saga.payload as TopUpPayload

        return TopUpView(
            id = saga.id,
            status = OrderStatus.of(saga.status),
            amount = payload.amount,
            // THE BALANCE IS READ AFTER THE SAGA RAN, not computed from the amount. On the refused
            // branch it is the old one, and that is the number the screen exists to state.
            balance = balances.balanceOf(accountId) ?: Money.zero(payload.amount.currency),
            declineReason = balances.declineReason(topUpId),
        )
    }

    class Params(
        val subscriberId: String,
        val amount: TopUpAmount,
    )
}

class FindTopUpUseCase(
    private val topUps: PetichRepository,
    private val balances: AccountBalances,
) {
    suspend operator fun invoke(params: Params): Result<TopUpView> =
        suspendRunCatching {
            val saga = topUps.findById(params.topUpId) ?: throw KonektException.NotFound("top-up")
            val payload =
                saga.payload as? TopUpPayload
                    // A saga id that exists and is not a top-up. NotFound rather than a cast failure:
                    // asking for somebody's purchase by its id must not confirm that it exists.
                    ?: throw KonektException.NotFound("top-up")

            // The owner check lives here rather than in the route, beside the subscriber id the
            // principal carries. `authenticate` proves the caller is SOME subscriber and says nothing
            // about whose top-up this is.
            if (payload.subscriberId != params.subscriberId) throw KonektException.NotFound("top-up")

            TopUpView(
                id = saga.id,
                status = OrderStatus.of(saga.status),
                amount = payload.amount,
                balance = balances.balanceOf(payload.accountId) ?: Money.zero(payload.amount.currency),
                declineReason = balances.declineReason(saga.id),
            )
        }

    class Params(
        val topUpId: String,
        val subscriberId: String,
    )
}
