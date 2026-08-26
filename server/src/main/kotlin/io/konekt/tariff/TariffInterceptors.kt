package io.konekt.tariff

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
import kotlin.time.Instant

// THE SECOND SAGA WITH A CONFIRMATION, and its value is reusing the first one's machinery without
// reusing its code. Three steps rather than the purchase's four: nothing is held, because a tariff
// change moves no money until the boundary — what it holds is a PROMISE, and the compensation is
// withdrawing it.
//
// The confirmation is not decoration. A tariff change is the sort of thing a subscriber should be
// asked twice about, and the suspend is what makes the TTL branch reachable at all: an unconfirmed
// change past its deadline is swept, and B-21's second acceptance criterion is that the current
// tariff is untouched when that happens.

// 1. VALIDATION — what can refuse before anything has happened.
class ValidateTariffChangeInterceptor(
    private val catalogue: TariffCatalogue,
    private val changes: TariffChanges,
) : PetichInterceptor<TariffChangePayload> {
    override val phase = PetichPhase.VALIDATION

    override fun supports(payload: PetichPayload) = payload is TariffChangePayload

    override suspend fun intercept(
        petich: Petich,
        payload: TariffChangePayload,
    ): InterceptorResult {
        catalogue.find(payload.toTariffId) ?: return InterceptorResult.Reject("that tariff is not in the catalogue")

        if (payload.toTariffId == payload.fromTariffId) {
            return InterceptorResult.Reject("that is the tariff you are already on")
        }

        // ONE PENDING CHANGE AT A TIME. Two would race for the same boundary and the later one would
        // win by accident of ordering — and a subscriber who asked twice would have no way to know
        // which they got.
        changes.pendingOf(payload.subscriberId)?.let {
            return InterceptorResult.Reject("a tariff change is already waiting for your confirmation")
        }

        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: TariffChangePayload,
    ) = Unit
}

// 2. AUTHORIZATION — write the promise down, then wait for the subscriber.
//
// One interceptor rather than two, exactly as the purchase saga does it: the record happens, and then
// the step returns Suspend. An interceptor that returned Suspend is NOT re-executed on resume, so the
// row is written once.
class RecordTariffChangeInterceptor(
    private val changes: TariffChanges,
    private val ttl: Duration,
) : PetichInterceptor<TariffChangePayload> {
    override val phase = PetichPhase.AUTHORIZATION

    override fun supports(payload: PetichPayload) = payload is TariffChangePayload

    override suspend fun intercept(
        petich: Petich,
        payload: TariffChangePayload,
    ): InterceptorResult {
        changes.record(
            changeId = petich.id,
            subscriberId = payload.subscriberId,
            fromTariffId = payload.fromTariffId,
            toTariffId = payload.toTariffId,
            effectiveAt = Instant.fromEpochMilliseconds(payload.effectiveAt),
        )

        return InterceptorResult.Suspend(requiredAction = ACTION_CONFIRM_TARIFF, ttl = ttl)
    }

    override suspend fun compensate(
        petich: Petich,
        payload: TariffChangePayload,
    ) {
        // THE ACCEPTANCE CRITERION AS A MECHANISM. A change nobody confirmed is cancelled, and
        // `currentTariffId` reads the newest APPLIED row whose boundary has passed — so a cancelled
        // one cannot become the answer. That is what "leaves the current tariff untouched" means.
        changes.cancel(petich.id)
    }
}

// 3. EXECUTION and the announcement, in one step.
//
// Nothing here can fail halfway: applying is a status change on a row that already exists. Splitting
// the announcement off would buy a rollback point between "applied" and "nobody told" — a state worth
// making unreachable rather than recoverable.
class ApplyTariffChangeInterceptor(
    private val changes: TariffChanges,
    private val events: TariffEvents,
) : PetichInterceptor<TariffChangePayload> {
    override val phase = PetichPhase.EXECUTION

    override fun supports(payload: PetichPayload) = payload is TariffChangePayload

    override suspend fun intercept(
        petich: Petich,
        payload: TariffChangePayload,
    ): InterceptorResult {
        changes.apply(petich.id)
        return InterceptorResult.Proceed(outboxEvents = listOf(events.changed(petich.id, payload)))
    }

    override suspend fun compensate(
        petich: Petich,
        payload: TariffChangePayload,
    ) {
        // Back to pending rather than to nothing: the row is the promise, and undoing the APPLYING
        // does not undo the asking. The step before this one owns the withdrawal.
        changes.cancel(petich.id)
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

fun tariffInterceptors(
    catalogue: TariffCatalogue,
    changes: TariffChanges,
    json: Json,
    confirmationTtl: Duration,
): List<PetichInterceptor<*>> =
    listOf(
        ValidateTariffChangeInterceptor(catalogue, changes),
        RecordTariffChangeInterceptor(changes, confirmationTtl),
        ApplyTariffChangeInterceptor(changes, TariffEvents(json)),
    )
