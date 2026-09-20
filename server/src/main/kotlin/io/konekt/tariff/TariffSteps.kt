package io.konekt.tariff

import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.PetichCheck
import io.github.youndie.petich.PetichCheckContext
import io.github.youndie.petich.PetichDefinition
import io.github.youndie.petich.PetichStep
import io.github.youndie.petich.PetichStepContext
import io.github.youndie.petich.petichDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Instant

// THE SECOND SAGA WITH A CONFIRMATION, and its value is reusing the first one's machinery without
// reusing its code. Three members rather than the purchase's four: nothing is held, because a tariff
// change moves no money until the boundary — what it holds is a PROMISE, and the compensation is
// withdrawing it.
//
// The confirmation is not decoration. A tariff change is the sort of thing a subscriber should be
// asked twice about, and the suspend is what makes the TTL branch reachable at all: an unconfirmed
// change past its deadline is swept, and B-21's second acceptance criterion is that the current
// tariff is untouched when that happens.
//
// AND THIS SAGA RECORDS NOTHING, unlike the other two. A `PetichStepRecord` earns its place where an
// undo cannot otherwise tell "it did not happen" from "it happened and said nothing" — the purchase
// releases money, the top-up reverses a credit, and both are wrong when they run against work that
// never occurred. Here both compensations are `changes.cancel(id)` against a row keyed by the saga's
// own id: no row, no update, no harm. The model asks for a record where the question arises, not from
// every member that acts.

// 1. VALIDATION — what can refuse before anything has happened.
//
// A check rather than a step, and the type says what the old empty `compensate` could only say by
// being empty.
class ValidateTariffChange(
    private val catalogue: TariffCatalogue,
    private val changes: TariffChanges,
) : PetichCheck<TariffChangePayload> {
    override suspend fun check(
        ctx: PetichCheckContext,
        payload: TariffChangePayload,
    ) {
        catalogue.find(payload.toTariffId) ?: return ctx.reject("that tariff is not in the catalogue")

        if (payload.toTariffId == payload.fromTariffId) {
            return ctx.reject("that is the tariff you are already on")
        }

        // ONE PENDING CHANGE AT A TIME. Two would race for the same boundary and the later one would
        // win by accident of ordering — and a subscriber who asked twice would have no way to know
        // which they got.
        changes.pendingOf(payload.subscriberId)?.let {
            return ctx.reject("a tariff change is already waiting for your confirmation")
        }
    }
}

// 2. AUTHORIZATION — write the promise down, then wait for the subscriber.
//
// One member rather than two, exactly as the purchase saga does it, and a STEP rather than an
// `authorize`: petich withdrew that overload (its B-39), because a phase meaning "before effects"
// that accepts members with effects tells a reader nothing. Writing the promise down IS an effect —
// the row exists afterwards and the compensation below cancels it. The record happens, and then the
// member suspends. A member that
// suspended is NOT re-executed on resume — the engine stores the index past it — so the row is
// written once.
class RecordTariffChange(
    private val changes: TariffChanges,
    private val ttl: Duration,
) : PetichStep<TariffChangePayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: TariffChangePayload,
    ) {
        changes.record(
            changeId = ctx.petich.id,
            subscriberId = payload.subscriberId,
            fromTariffId = payload.fromTariffId,
            toTariffId = payload.toTariffId,
            effectiveAt = Instant.fromEpochMilliseconds(payload.effectiveAt),
        )

        return ctx.suspendFor(ACTION_CONFIRM_TARIFF, ttl)
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: TariffChangePayload,
    ) {
        // THE ACCEPTANCE CRITERION AS A MECHANISM. A change nobody confirmed is cancelled, and
        // `currentTariffId` reads the newest APPLIED row whose boundary has passed — so a cancelled
        // one cannot become the answer. That is what "leaves the current tariff untouched" means.
        changes.cancel(ctx.petich.id)
    }
}

// 3. EXECUTION and the announcement, in one member.
//
// Nothing here can fail halfway: applying is a status change on a row that already exists. Splitting
// the announcement off would buy a rollback point between "applied" and "nobody told" — a state worth
// making unreachable rather than recoverable.
class ApplyTariffChange(
    private val changes: TariffChanges,
    private val events: TariffEvents,
) : PetichStep<TariffChangePayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: TariffChangePayload,
    ) {
        changes.apply(ctx.petich.id)
        ctx.emit(events.changed(ctx.petich.id, payload))
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: TariffChangePayload,
    ) {
        // CANCELLED, and the comment this replaces said "back to pending rather than to nothing"
        // while the code cancelled — `TariffChanges` has no operation that returns a row to pending,
        // so the sentence described a design nobody had built. The behaviour is right and unchanged:
        // a change whose application was rolled back is not a change still waiting to be confirmed.
        // The member before this one cancels too, and cancelling twice is one row reaching the same
        // status.
        changes.cancel(ctx.petich.id)
    }
}

class TariffEvents(
    private val json: Json,
) {
    fun changed(
        changeId: String,
        payload: TariffChangePayload,
    ): OutboxEvent =
        // The id is the change plus the kind rather than a random one, so the same change cannot be
        // announced twice: delivery is at-least-once, and a consumer keyed on this id can tell a
        // redelivery from a second change.
        TariffEvent(
            id = "$changeId:tariff.changed",
            type = "tariff.changed",
            payload =
                json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf(
                            "changeId" to JsonPrimitive(changeId),
                            "subscriberId" to JsonPrimitive(payload.subscriberId),
                            "fromTariffId" to JsonPrimitive(payload.fromTariffId),
                            "toTariffId" to JsonPrimitive(payload.toTariffId),
                            "effectiveAt" to JsonPrimitive(payload.effectiveAt),
                        ),
                    ),
                ),
        )

    private data class TariffEvent(
        override val id: String,
        override val type: String,
        override val payload: String,
    ) : OutboxEvent
}

const val ACTION_CONFIRM_TARIFF = "CONFIRM_TARIFF"

// The tariff-change saga, in the order it runs.
fun tariffPetich(
    catalogue: TariffCatalogue,
    changes: TariffChanges,
    json: Json,
    confirmationTtl: Duration,
): PetichDefinition<TariffChangePayload> =
    // THE TYPE COMES FROM THE CONSTANT the rest of the code already uses, never spelled by hand.
    petichDefinition(TARIFF_CHANGE_SAGA_TYPE) {
        validate("catalogue-and-pending", ValidateTariffChange(catalogue, changes))
        step("record-change", RecordTariffChange(changes, confirmationTtl))
        step("apply", ApplyTariffChange(changes, TariffEvents(json)))
    }
