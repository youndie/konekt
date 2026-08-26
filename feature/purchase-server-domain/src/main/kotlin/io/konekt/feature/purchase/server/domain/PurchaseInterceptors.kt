package io.konekt.feature.purchase.server.domain

import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.roaming.server.domain.Zones
import io.konekt.feature.usage.server.domain.UsageGrants
import io.konekt.time.KonektClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.petich.InterceptorResult
import ru.workinprogress.petich.OutboxEvent
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichInterceptor
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// FOUR INTERCEPTORS, NOT SIX, and the number is measured rather than aesthetic. petich writes the
// saga row at every step boundary — its own figure is about 9 database writes for four steps against
// about 17 for six, taken through pg_stat_user_tables — and this is the most frequent operation in
// the product. The reading cost of holding the funds and asking for confirmation in one step is a
// paragraph; the writing cost of splitting them is eight extra writes per purchase, forever.

// 1. VALIDATION — the rules that can refuse before anything has happened.
//
// A Reject here leaves nothing to undo, which is why it is a separate step from the hold rather than
// a check inside it: the saga ends REJECTED and the subscriber is told why, with no compensation
// having run at all.
class ValidatePurchaseInterceptor(
    private val plans: PlanCatalog,
    private val balances: AccountBalances,
) : PetichInterceptor<PurchasePayload> {
    override val phase = PetichPhase.VALIDATION

    override fun supports(payload: PetichPayload) = payload is PurchasePayload

    override suspend fun intercept(
        petich: Petich,
        payload: PurchasePayload,
    ): InterceptorResult {
        val plan = plans.find(payload.planId) ?: return InterceptorResult.Reject("that plan is not in the catalogue")
        if (!plan.onSale) return InterceptorResult.Reject("that plan is no longer on sale")
        if (plan.price != payload.price) {
            // The price the subscriber was shown is not the price now. Refusing is the only honest
            // answer: charging the new one is a surprise, and charging the old one is a catalogue
            // anybody can pin by keeping a screen open.
            return InterceptorResult.Reject("the price has changed, please look again")
        }

        val account = balances.findAccountOf(payload.subscriberId) ?: return InterceptorResult.Reject("no account")
        if (account.balance < payload.price) {
            return InterceptorResult.Reject("the balance does not cover this")
        }

        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: PurchasePayload,
    ) = Unit
}

// 2. AUTHORIZATION — hold the money, then wait for the subscriber.
//
// One interceptor rather than two, per D5. The hold happens, and then the step returns Suspend: the
// saga stops, holding neither a thread nor a database connection, and continues on a later HTTP
// request. An interceptor that returned Suspend is deliberately NOT re-executed on resume, so the
// money is held exactly once.
class HoldFundsInterceptor(
    private val balances: AccountBalances,
    private val entitlements: Entitlements,
    private val events: PurchaseEvents,
    private val ttl: Duration,
) : PetichInterceptor<PurchasePayload> {
    override val phase = PetichPhase.AUTHORIZATION

    override fun supports(payload: PetichPayload) = payload is PurchasePayload

    override suspend fun intercept(
        petich: Petich,
        payload: PurchasePayload,
    ): InterceptorResult {
        // The refusal lives in the database, not in a read followed by a check: two purchases started
        // together both pass the latter, and what they would overspend is real money.
        if (!balances.hold(payload.accountId, petich.id, payload.price)) {
            return InterceptorResult.Reject("the balance does not cover this")
        }

        entitlements.createPending(petich.id, payload.subscriberId, payload.planId, payload.price)

        // THE TTL IS THIS STEP'S, not the engine's, and five minutes is the number. It is the same
        // order as the one-time code the confirmation usually involves, and it bounds how long the
        // subscriber's own money sits held on a purchase they walked away from. Long enough to read a
        // message and type six digits; short enough that an abandoned tab does not cost them their
        // balance for an hour.
        return InterceptorResult.Suspend(requiredAction = ACTION_CONFIRM, ttl = ttl)
    }

    override suspend fun compensate(
        petich: Petich,
        payload: PurchasePayload,
    ) {
        // The rollback the canvas draws in money. Both halves, and in this order: the balance first,
        // because that is the number the subscriber is looking at.
        balances.release(payload.accountId, petich.id, payload.price)
        entitlements.cancel(petich.id)
    }

    // THE REVERSAL IS ANNOUNCED HERE, and putting it on the announcing step instead was a mistake a
    // test caught. Compensation walks back only through steps that actually RAN forward, and a
    // purchase abandoned at the confirmation never reaches POST_PROCESSING — so an announcement
    // hanging off that step is an announcement that never happens for the one case it exists for.
    //
    // The step being undone is this one: the hold. So this is where saying so belongs.
    override suspend fun compensateWithEvents(
        petich: Petich,
        payload: PurchasePayload,
    ): List<OutboxEvent> {
        compensate(petich, payload)
        return listOf(events.reversed(petich.id, payload))
    }
}

// 3. EXECUTION — settle with the provider, and make the package usable.
//
// Settling and provisioning are ONE step rather than two, which keeps the saga at four interceptors
// (D5). They are one thing from the product's side — "make it real" — and splitting them would buy a
// rollback point between a captured payment and an inactive package, which is a state nobody wants to
// be able to reach.
class ProvisionInterceptor(
    private val balances: AccountBalances,
    private val entitlements: Entitlements,
    private val payments: PaymentGateway,
    // The usage feature's port, named from here on purpose. What an allowance is made of is that
    // domain's business — see UsageGrants — and a purchase only knows it bought a plan.
    private val grants: UsageGrants,
    // The roaming feature's port, alongside the usage one and for the same reason: a purchase knows
    // it bought a package for a zone, and what a dormant package IS belongs to that domain.
    private val roaming: RoamingPackages,
    private val clock: KonektClock,
) : PetichInterceptor<PurchasePayload> {
    override val phase = PetichPhase.EXECUTION

    override fun supports(payload: PetichPayload) = payload is PurchasePayload

    override suspend fun intercept(
        petich: Petich,
        payload: PurchasePayload,
    ): InterceptorResult {
        val settlement = payments.settle(petich.id, payload.price)
        if (settlement is PaymentGateway.Settlement.Declined) {
            // Recorded HERE, by the step that learned it. petich carries a Compensate reason to its
            // metrics and does not persist one, and the compensating step — the hold — has no way to
            // know why it is being undone. So the reason is written where it is known, and the
            // rollback screen reads it back.
            balances.recordDecline(payload.accountId, petich.id, payload.price, settlement.reason)
            return InterceptorResult.Compensate(settlement.reason)
        }

        balances.capture(payload.accountId, petich.id, payload.price)
        entitlements.activate(petich.id)

        // THE ALLOWANCE LANDS HERE, in the same step as the capture and the activation, because they
        // are one thing from the product's side — "make it real". A purchase that captured the money
        // and granted nothing is a subscriber who paid for ten gigabytes and has a home screen that
        // says zero.
        //
        // WHAT "make it real" MEANS DEPENDS ON THE ZONE, and this branch is the only difference
        // between buying data for home and buying data for a trip. The saga is the same saga —
        // same validation, same hold, same settlement, same compensation shape — because the money
        // side of the two is identical and only the provisioning differs. That is D-19's claim, and
        // it is four lines of it.
        grantAllowance(petich, payload)

        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: PurchasePayload,
    ) {
        // Only what this step did. The hold is the previous step's to release, and compensating it
        // here as well would return the money twice — which is the shape of mistake a saga makes
        // easy, because every step can see everything.
        entitlements.cancel(petich.id)
        // Including the allowance, which is the half that is easy to forget: money that comes back
        // while the gigabytes stay is a rollback that costs the operator rather than nobody.
        //
        // Branching on the SAME condition as the grant, which is why both live in one place below. Two
        // copies of `if (zone == HOME)` is how a rolled-back roaming purchase ends up revoking a home
        // allowance the subscriber actually paid for.
        revokeAllowance(petich, payload)
    }

    private suspend fun grantAllowance(
        petich: Petich,
        payload: PurchasePayload,
    ) {
        if (payload.dataMb <= 0) return
        if (payload.zone == Zones.HOME) {
            grants.grantPlanAllowance(payload.subscriberId, payload.dataMb)
        } else {
            // DORMANT, and there is no flag here that could make it otherwise. The subscriber is
            // standing at home with a package for Turkey; nothing about paying for it means the trip
            // has started.
            roaming.grant(
                orderId = petich.id,
                subscriberId = payload.subscriberId,
                zone = payload.zone,
                limitMb = payload.dataMb,
                validForDays = payload.validForDays,
                purchasedAt = clock.now(),
            )
        }
    }

    private suspend fun revokeAllowance(
        petich: Petich,
        payload: PurchasePayload,
    ) {
        if (payload.dataMb <= 0) return
        if (payload.zone == Zones.HOME) {
            grants.revokePlanAllowance(payload.subscriberId, payload.dataMb)
        } else {
            // The same key the grant used. The order IS the saga, so a compensation can
            // always name exactly what it granted rather than searching for something that looks
            // like it.
            roaming.revoke(petich.id)
        }
    }
}

// 4. POST_PROCESSING — say what happened, in the same transaction as the state change.
//
// The event is an INTENT to publish rather than a publication: petich writes it into the outbox
// inside the transaction that completes the saga, which is what makes "the work happened but nobody
// was told" structurally impossible. Delivery is the relay's job.
class AnnouncePurchaseInterceptor(
    private val events: PurchaseEvents,
) : PetichInterceptor<PurchasePayload> {
    override val phase = PetichPhase.POST_PROCESSING

    override fun supports(payload: PetichPayload) = payload is PurchasePayload

    override suspend fun intercept(
        petich: Petich,
        payload: PurchasePayload,
    ): InterceptorResult =
        InterceptorResult.Proceed(
            outboxEvents = listOf(events.completed(petich.id, payload)),
        )

    // Nothing to undo: this step only announced, and the announcement of the reversal belongs to the
    // step whose work is being reversed.
    override suspend fun compensate(
        petich: Petich,
        payload: PurchasePayload,
    ) = Unit
}

// Both events, built in one place, so the two never drift in shape.
class PurchaseEvents(
    private val json: Json,
) {
    fun completed(
        orderId: String,
        payload: PurchasePayload,
    ): OutboxEvent = event("purchase.completed", orderId, payload)

    fun reversed(
        orderId: String,
        payload: PurchasePayload,
    ): OutboxEvent = event("purchase.reversed", orderId, payload)

    private fun event(
        type: String,
        orderId: String,
        payload: PurchasePayload,
    ): OutboxEvent =
        // The id is the order plus the kind rather than a random one, so the same completion cannot
        // be announced twice: delivery is at-least-once, and a consumer keyed on this id can tell a
        // redelivery from a second purchase.
        PurchaseEvent(
            id = "$orderId:$type",
            type = type,
            payload =
                json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf(
                            "orderId" to JsonPrimitive(orderId),
                            "subscriberId" to JsonPrimitive(payload.subscriberId),
                            "planId" to JsonPrimitive(payload.planId),
                            "amountMinor" to JsonPrimitive(payload.price.minorUnits),
                            "currency" to JsonPrimitive(payload.price.currency.name),
                        ),
                    ),
                ),
        )

    // OutboxEvent is an interface — petich carries the intent, not a class of ours — so the concrete
    // shape is the application's.
    private data class PurchaseEvent(
        override val id: String,
        override val type: String,
        override val payload: String,
    ) : OutboxEvent
}

val DEFAULT_CONFIRMATION_TTL: Duration = 5.minutes
