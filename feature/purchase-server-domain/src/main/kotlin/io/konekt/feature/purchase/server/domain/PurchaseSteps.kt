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
import io.github.youndie.petich.petichDefinition
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.roaming.server.domain.Zones
import io.konekt.feature.usage.server.domain.UsageGrants
import io.konekt.time.KonektClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// FOUR MEMBERS, NOT SIX, and the number is measured rather than aesthetic. petich writes the saga row
// at every member boundary — its own figure is about 9 database writes for four against about 17 for
// six, taken through pg_stat_user_tables — and this is the most frequent operation in the product.
// The reading cost of holding the funds and asking for confirmation in one member is a paragraph; the
// writing cost of splitting them is eight extra writes per purchase, forever.
//
// The order is now written at the bottom of this file, where it can be read, rather than
// reconstructed from four `phase` fields and four priorities.

// WHAT AN ACTING MEMBER DID, beside that member's key in the saga's own row — and what such a record
// can and cannot say. petich compensates the member whose outcome it never learned: a throw, a lost
// connection, a deadline that fired after the far side committed. The record is written by the
// member after its calls return, so in exactly that case it is absent while the effect may well have
// landed. It is evidence of what the member reached, never of what did not happen
// (youndie/petich B-43). An undo that can name its effect undoes by the name; the record is for an
// undo that has nothing else to go on.
//
// Both are registered in the serializers module beside the payloads. A record whose class is not
// registered fails to deserialize, and so does the saga row that carries it.

// NEITHER IS WRITTEN ANY MORE, and both are still registered. `HoldFunds` recorded `Held` and
// `Provision` recorded `Provisioned` until each undo was found to return quietly without its record —
// a hold whose entitlement write threw, or a hold or a grant whose own answer was lost, kept the
// subscriber's money or allowance for good. Both members undo by the order's name now and ask for no
// record. The classes stay because rows written before those changes carry them, and one of those
// that cannot be decoded is an order that can no longer be confirmed, swept or shown.
@Serializable
@SerialName("purchase_held")
data class Held(
    val minorUnits: Long,
) : PetichStepRecord()

@Serializable
@SerialName("purchase_provisioned")
data class Provisioned(
    val zone: String,
) : PetichStepRecord()

// 1. VALIDATION — the rules that can refuse before anything has happened.
//
// A CHECK RATHER THAN A STEP, and the type is the statement the old empty `compensate` could only
// make by being empty: nothing here has anything to undo. The saga ends REJECTED and the subscriber
// is told why, with no compensation having run at all.
class ValidatePurchase(
    private val plans: PlanCatalog,
    private val balances: AccountBalances,
) : PetichCheck<PurchasePayload> {
    override suspend fun check(
        ctx: PetichCheckContext,
        payload: PurchasePayload,
    ) {
        // THE ACCOUNT FIRST, and it used to be last. Every refusal below wants to be RECORDED, the
        // record is a ledger row, and a ledger row needs an account — so an order refused before the
        // account was resolved had nowhere to put its reason, and the screen was left with a sentence
        // that named none of the five (`B-68`).
        //
        // Which refusal wins when several apply therefore changed, and only between broken states: a
        // subscriber with no account asking for a plan that does not exist now hears about the
        // account. Both are states this product should not be able to reach.
        val account =
            balances.findAccountOf(payload.subscriberId)
                // Nowhere to write it, by definition. The screen's generic sentence is the honest
                // answer here and this is the only branch that gets it.
                ?: return ctx.reject(PurchaseRefusals.NO_ACCOUNT)

        val plan = plans.find(payload.planId) ?: return refuse(ctx, account, payload, PurchaseRefusals.NO_SUCH_PLAN)
        if (!plan.onSale) return refuse(ctx, account, payload, PurchaseRefusals.NOT_ON_SALE)
        if (plan.price != payload.price) {
            // The price the subscriber was shown is not the price now. Refusing is the only honest
            // answer: charging the new one is a surprise, and charging the old one is a catalogue
            // anybody can pin by keeping a screen open.
            return refuse(ctx, account, payload, PurchaseRefusals.PRICE_CHANGED)
        }

        if (account.balance < payload.price) {
            return refuse(ctx, account, payload, PurchaseRefusals.INSUFFICIENT_FUNDS)
        }
    }

    // RECORDED THROUGH OUR OWN PORT, AND THAT IS NOT A STYLE CHOICE. petich keeps no reason of its
    // own and a refusal ends the saga, so a refusal not written down here is one the screen can never
    // state. `ctx.emit` would be the model's way to say it — and a member's emitted events are
    // dropped on every outcome but Proceed (youndie/petich B-35), so a refusal announced that way
    // would vanish. The ledger row is the durable answer either way: zero-sum, like the provider's
    // decline it sits beside, carrying the word rather than an amount.
    private suspend fun refuse(
        ctx: PetichCheckContext,
        account: AccountSnapshot,
        payload: PurchasePayload,
        code: String,
    ) {
        balances.recordDecline(account.id, ctx.petich.id, payload.price, code)
        return ctx.reject(code)
    }
}

// 2. EXECUTION — hold the money, then wait for the subscriber.
//
// ONE MEMBER RATHER THAN TWO, per D5 — and a STEP, which is the correction petich made to D3 on our
// account. That decision existed because this member holds money and then waits, and `authorize` was
// widened to take it; what it was really saying is that a hold is not an *authorisation* in petich's
// sense. In payments the word means the hold itself; in petich's phases it asks whether it is
// allowed. So this sits in EXECUTION, above the members that depend on it, and the rollback gets the
// order it should always have had. The hold happens, and then the member suspends: the saga stops,
// holding neither a
// thread nor a database connection, and continues on a later HTTP request. A member that suspended is
// deliberately NOT re-executed on resume — the engine stores the index PAST it — so the money is held
// exactly once.
class HoldFunds(
    private val balances: AccountBalances,
    private val entitlements: Entitlements,
    private val events: PurchaseEvents,
    private val ttl: Duration,
) : PetichStep<PurchasePayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: PurchasePayload,
    ) {
        // The refusal lives in the database, not in a read followed by a check: two purchases started
        // together both pass the latter, and what they would overspend is real money.
        if (!balances.hold(payload.accountId, ctx.petich.id, payload.price)) {
            // THE SAME REFUSAL AS THE VALIDATION MEMBER'S, recorded the same way. This one is the
            // authoritative check — it refuses in the database rather than after a read — so a
            // purchase can reach it having passed the earlier one, and a subscriber who lands here
            // needs the same sentence and the same way out.
            balances.recordDecline(payload.accountId, ctx.petich.id, payload.price, PurchaseRefusals.INSUFFICIENT_FUNDS)
            return ctx.reject(PurchaseRefusals.INSUFFICIENT_FUNDS)
        }

        // A SECOND WRITE IN A SECOND TRANSACTION, and the reason the undo below asks the ledger rather
        // than this member. If it throws, the hold above has committed and nothing this member could
        // have written after it exists.
        entitlements.createPending(ctx.petich.id, payload.subscriberId, payload.planId, payload.price)

        // THE TTL IS THIS MEMBER'S, not the engine's, and five minutes is the number. It is the same
        // order as the one-time code the confirmation usually involves, and it bounds how long the
        // subscriber's own money sits held on a purchase they walked away from. Long enough to read a
        // message and type six digits; short enough that an abandoned tab does not cost them their
        // balance for an hour.
        return ctx.suspendFor(ACTION_CONFIRM, ttl)
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: PurchasePayload,
    ) {
        // BY THE ORDER'S NAME, AND UNCONDITIONALLY. This is called for a member that threw as well as
        // for one that suspended, and the first is where the hold may have committed while nothing
        // here learned it: an entitlement write that failed, a hold whose answer was lost. konekt#49
        // asked a step record first and returned quietly without one, and in exactly those cases the
        // record was absent and the money stayed held (`HoldRollbackTest`). The order id is the name
        // `hold` was given before it acted, so the release asks for it and the ledger answers —
        // a no-op when nothing was held, which is the other half of the same case.
        //
        // The balance first, because that is the number the subscriber is looking at.
        val returned = balances.release(payload.accountId, ctx.petich.id, payload.price)
        // Keyed by the order too: an UPDATE that finds no row cancels nothing.
        entitlements.cancel(ctx.petich.id)

        // THE REVERSAL IS ANNOUNCED HERE, and putting it on the announcing member instead was a
        // mistake a test caught. Compensation walks back only through members that actually RAN
        // forward, and a purchase abandoned at the confirmation never reaches POST_PROCESSING — so an
        // announcement hanging off that member is one that never happens for the single case it
        // exists for.
        //
        // The member being undone is this one: the hold. So this is where saying so belongs — and
        // only when the ledger says money came back. A hold that never landed moved nothing, and a
        // reversal announced for it would be news about a purchase nobody paid for.
        if (returned) ctx.emit(events.reversed(ctx.petich.id, payload))
    }
}

// 3. EXECUTION — settle with the provider, and make the package usable.
//
// Settling and provisioning are ONE member rather than two, which keeps the saga at four (D5). They
// are one thing from the product's side — "make it real" — and splitting them would buy a rollback
// point between a captured payment and an inactive package, which is a state nobody wants to be able
// to reach.
class Provision(
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
) : PetichStep<PurchasePayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: PurchasePayload,
    ) {
        val settlement = payments.settle(ctx.petich.id, payload.price)
        if (settlement is PaymentGateway.Settlement.Declined) {
            // Recorded HERE, by the member that learned it. petich carries a fault reason to its
            // metrics and does not persist one, and the compensating member — the hold — has no way
            // to know why it is being undone. So the reason is written where it is known, and the
            // rollback screen reads it back.
            balances.recordDecline(payload.accountId, ctx.petich.id, payload.price, settlement.reason)
            return ctx.fail(settlement.reason)
        }

        balances.capture(payload.accountId, ctx.petich.id, payload.price)
        entitlements.activate(ctx.petich.id)

        // THE ALLOWANCE LANDS HERE, in the same member as the capture and the activation, because
        // they are one thing from the product's side — "make it real". A purchase that captured the
        // money and granted nothing is a subscriber who paid for ten gigabytes and has a home screen
        // that says zero.
        //
        // WHAT "make it real" MEANS DEPENDS ON THE ZONE, and this branch is the only difference
        // between buying data for home and buying data for a trip. The saga is the same saga — same
        // validation, same hold, same settlement, same compensation shape — because the money side of
        // the two is identical and only the provisioning differs. That is D-19's claim, and it is
        // four lines of it.
        grantAllowance(ctx, payload)
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: PurchasePayload,
    ) {
        // BY THE ORDER'S NAME, AND UNCONDITIONALLY — the hold's rule, for the same reason (B-130).
        // This is called for a member that threw as well as for one that finished, and a grant that
        // committed and lost its answer is a member that threw having reached nothing it could have
        // recorded: the step record asked first here was absent in exactly that case, and the
        // allowance stayed. Every write below is keyed by the order the grant was given before it
        // acted, and each is a no-op when nothing is under it — which is the other half of the same
        // case: a grant that never landed must not take an earlier plan's allowance with it.
        //
        // Only what this member did. The hold is the previous member's to release, and compensating
        // it here as well would return the money twice — which is the shape of mistake a saga makes
        // easy, because every member can see everything.
        entitlements.cancel(ctx.petich.id)
        // Including the allowance, which is the half that is easy to forget: money that comes back
        // while the gigabytes stay is a rollback that costs the operator rather than nobody.
        //
        // BOTH BRANCHES, rather than the one the zone picks. An order granted one or the other, and
        // taking back by its name from the branch it never touched finds nothing — so the undo does
        // not have to agree with the grant about which branch ran.
        grants.revokePlanAllowance(ctx.petich.id)
        roaming.revoke(ctx.petich.id)
    }

    private suspend fun grantAllowance(
        ctx: PetichStepContext,
        payload: PurchasePayload,
    ) {
        if (payload.dataMb <= 0 && payload.minutes <= 0 && payload.messages <= 0) return
        if (payload.zone == Zones.HOME) {
            // Under the order's name, which is what makes this safe to run twice and possible to
            // take back: petich runs a member again after a conflict on its position write, and a
            // second grant under the same order adds nothing.
            grants.grantPlanAllowance(
                ctx.petich.id,
                payload.subscriberId,
                payload.dataMb,
                payload.minutes,
                payload.messages,
            )
        } else {
            // DORMANT, and there is no flag here that could make it otherwise. The subscriber is
            // standing at home with a package for Turkey; nothing about paying for it means the trip
            // has started.
            roaming.grant(
                orderId = ctx.petich.id,
                subscriberId = payload.subscriberId,
                zone = payload.zone,
                limitMb = payload.dataMb,
                validForDays = payload.validForDays,
                purchasedAt = clock.now(),
            )
        }
    }
}

// 4. POST_PROCESSING — say what happened, in the same transaction as the state change.
//
// The event is an INTENT to publish rather than a publication: petich writes it into the outbox
// inside the transaction that completes the saga, which is what makes "the work happened but nobody
// was told" structurally impossible. Delivery is the relay's job.
class AnnouncePurchase(
    private val events: PurchaseEvents,
) : PetichAnnouncement<PurchasePayload> {
    override suspend fun announce(
        ctx: PetichAnnouncementContext,
        payload: PurchasePayload,
    ) {
        ctx.emit(events.completed(ctx.petich.id, payload))
    }
}

// The purchase saga, in the order it runs.
fun purchasePetich(
    balances: AccountBalances,
    entitlements: Entitlements,
    plans: PlanCatalog,
    payments: PaymentGateway,
    grants: UsageGrants,
    roaming: RoamingPackages,
    clock: KonektClock,
    json: Json,
    confirmationTtl: Duration = DEFAULT_CONFIRMATION_TTL,
): PetichDefinition<PurchasePayload> {
    val events = PurchaseEvents(json)
    // THE TYPE COMES FROM THE CONSTANT the rest of the code already uses, never spelled by hand. A
    // definition declared `purchse` against a row that says `purchase` used to run zero members and
    // report the saga complete; petich refuses that now (youndie/petich#78), and the constant is what
    // keeps the refusal from being something anybody has to see.
    return petichDefinition(PURCHASE_SAGA_TYPE) {
        validate("plan-and-funds", ValidatePurchase(plans, balances))
        step("hold-funds", HoldFunds(balances, entitlements, events, confirmationTtl))
        step("provision", Provision(balances, entitlements, payments, grants, roaming, clock))
        announce("announce", AnnouncePurchase(events))
    }
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
