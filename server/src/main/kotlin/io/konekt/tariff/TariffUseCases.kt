package io.konekt.tariff

import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.time.KonektClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.ResumePayload
import ru.workinprogress.petich.SimpleEnrichedPayload
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The answer to a tariff confirmation. It carries nothing yet and still exists as a type, for the
// reason `PurchaseConfirmation` gives: a resume with no payload and a resume that decided something
// are different requests.
@Serializable
@SerialName("tariff_confirmation")
class TariffConfirmation : ResumePayload()

// What a change looks like to a subscriber. `currentTariffId` sits beside the requested one because
// both are true until the boundary — which is the whole of B-21's first acceptance criterion.
data class TariffChangeView(
    val changeId: String,
    val status: OrderStatus,
    val currentTariffId: String,
    val requestedTariffId: String,
    // BOTH NAMES, RESOLVED HERE (`B-96`). `TariffChangeScreen` used to take the whole catalogue
    // beside this view so it could turn two ids into two names — a renderer that can look things up
    // is a renderer that can answer a different question than the one the use case answered. The ids
    // stay because the DTO response carries them; the titles are what a person reads.
    val currentTariffTitle: String,
    val requestedTariffTitle: String,
    val effectiveAt: Instant,
    val requiredAction: String?,
)

// A CHANGE ASKED FOR AND NOT YET CONFIRMED, with everything a screen needs to say so already
// resolved. TWO screens draw this — the profile and the tariff catalogue — and they used to compose
// the same sentence from the same record in two places, one of them a routing file.
//
// The `changeId` is here because the catalogue's banner carries the way back to the confirmation.
// The profile's banner does not, today; carrying the id costs nothing and is what a second control
// would need.
data class PendingTariffChange(
    val changeId: String,
    val toTariffTitle: String,
    val effectiveAt: Instant,
)

// The one place a pending change becomes a screen's answer. Two callers, so that a subscriber cannot
// be told one thing on the profile and another in the catalogue.
internal suspend fun pendingTariffChangeOf(
    subscriberId: String,
    changes: TariffChanges,
    catalogue: TariffCatalogue,
): PendingTariffChange? =
    changes.pendingOf(subscriberId)?.let {
        PendingTariffChange(
            changeId = it.changeId,
            toTariffTitle = catalogue.titleOf(it.toTariffId),
            effectiveAt = it.effectiveAt,
        )
    }

// THE CATALOGUE SCREEN'S ANSWERS. The list of tariffs travels whole and undecorated — a catalogue
// screen rendering a catalogue looks nothing up, the same as `PlansScreen` — and what it could NOT
// answer for itself is the other two: which one this subscriber is on, and whether a change is
// already waiting.
data class TariffsView(
    val tariffs: List<Tariff>,
    val currentTariffId: String,
    val pending: PendingTariffChange? = null,
)

class ViewTariffsUseCase(
    private val catalogue: TariffCatalogue,
    private val changes: TariffChanges,
) {
    suspend operator fun invoke(subscriberId: String): Result<TariffsView> =
        suspendRunCatching {
            TariffsView(
                tariffs = catalogue.all(),
                // The catalogue's default when the log has no rows: a subscriber who never changed
                // has nothing recorded, and what "the beginning" is called belongs to the catalogue
                // rather than to the log.
                currentTariffId = changes.currentTariffId(subscriberId) ?: catalogue.default.id,
                pending = pendingTariffChangeOf(subscriberId, changes, catalogue),
            )
        }
}

// A TARIFF'S TITLE, falling back to its id. An id on a screen is a value that leaked out of a table
// and `tr-standard` is not what a subscriber calls anything — but a tariff the catalogue has
// forgotten is still what they are on, so printing the key beats printing a blank.
fun TariffCatalogue.titleOf(tariffId: String): String = find(tariffId)?.title ?: tariffId

@OptIn(ExperimentalUuidApi::class)
class StartTariffChangeUseCase(
    private val engine: PetichEngine,
    private val sagas: PetichRepository,
    private val catalogue: TariffCatalogue,
    private val changes: TariffChanges,
    private val clock: KonektClock,
) {
    suspend operator fun invoke(params: Params): Result<TariffChangeView> =
        suspendRunCatching {
            catalogue.find(params.tariffId) ?: throw KonektException.NotFound("tariff")

            val current = changes.currentTariffId(params.subscriberId) ?: catalogue.default.id
            val changeId = Uuid.random().toString()

            // DECIDED HERE, not at apply time. A subscriber told "from the first of next month" and
            // confirming on the thirty-first must get the date they were shown, not the one the clock
            // produces a minute later.
            val effectiveAt = BillingBoundary.nextAfter(clock)

            engine.process(
                Petich(
                    id = changeId,
                    type = TARIFF_CHANGE_SAGA_TYPE,
                    status = PetichStatus.DRAFT,
                    payload =
                        TariffChangePayload(
                            subscriberId = params.subscriberId,
                            fromTariffId = current,
                            toTariffId = params.tariffId,
                            effectiveAt = effectiveAt.toEpochMilliseconds(),
                        ),
                    enrichedPayload = SimpleEnrichedPayload(),
                ),
            )

            viewOf(changeId, params.subscriberId)
        }

    private suspend fun viewOf(
        changeId: String,
        subscriberId: String,
    ): TariffChangeView = tariffChangeViewOf(changeId, subscriberId, sagas, catalogue, changes)

    class Params(
        val subscriberId: String,
        val tariffId: String,
    )
}

// ONE VIEW BUILDER FOR THREE CALLERS, and it is here rather than copied because the third caller is
// what `B-86` added: a screen has to be able to READ a change, not only to start or confirm one.
//
// There were two copies before that — `StartTariffChangeUseCase.viewOf` and the tail of
// `ConfirmTariffChangeUseCase` — and they had already begun to differ in the field that matters: one
// derived `requiredAction` from the saga's status and the other hard-coded `null`. A third copy is
// how a screen ends up disagreeing with the route about whether a change is still waiting. The eSIM
// wizard learned exactly this (`B-66`), where one of two paths carried the issued profile and the
// other did not.
//
// THE OWNER CHECK IS IN HERE for the same reason it is in the confirm use case: `authenticate` proves
// the caller is somebody and says nothing about whose change this is. NotFound and not Forbidden —
// asking for a stranger's change must not confirm that it exists.
internal suspend fun tariffChangeViewOf(
    changeId: String,
    subscriberId: String,
    sagas: PetichRepository,
    catalogue: TariffCatalogue,
    changes: TariffChanges,
): TariffChangeView {
    // Read back rather than inferred from an engine's answer: that answer says what happened on this
    // pass, and what a screen needs is where the change now stands. After a Suspend those differ.
    val saga = sagas.findById(changeId) ?: throw KonektException.NotFound("tariff change")
    val payload =
        saga.payload as? TariffChangePayload
            // A saga id that exists and is not a tariff change.
            ?: throw KonektException.NotFound("tariff change")
    if (payload.subscriberId != subscriberId) throw KonektException.NotFound("tariff change")

    // The CURRENT tariff, read after the saga ran: the old one while the change is pending, and the
    // new one only once the boundary row says applied.
    val currentTariffId = changes.currentTariffId(subscriberId) ?: catalogue.default.id

    return TariffChangeView(
        changeId = changeId,
        status = OrderStatus.of(saga.status),
        currentTariffId = currentTariffId,
        requestedTariffId = payload.toTariffId,
        currentTariffTitle = catalogue.titleOf(currentTariffId),
        requestedTariffTitle = catalogue.titleOf(payload.toTariffId),
        effectiveAt = Instant.fromEpochMilliseconds(payload.effectiveAt),
        requiredAction = if (saga.status == PetichStatus.PENDING_SIGNATURE) ACTION_CONFIRM_TARIFF else null,
    )
}

// READING ONE CHANGE, which is what a screen does and what nothing could do before `B-86`. The routes
// could START and CONFIRM a change and there was no way to look at one — so a subscriber who left the
// application between the two had no way back to the confirmation they were asked for.
class ViewTariffChangeUseCase(
    private val sagas: PetichRepository,
    private val catalogue: TariffCatalogue,
    private val changes: TariffChanges,
) {
    suspend operator fun invoke(params: Params): Result<TariffChangeView> =
        suspendRunCatching { tariffChangeViewOf(params.changeId, params.subscriberId, sagas, catalogue, changes) }

    class Params(
        val changeId: String,
        val subscriberId: String,
    )
}

class ConfirmTariffChangeUseCase(
    private val engine: PetichEngine,
    private val sagas: PetichRepository,
    private val catalogue: TariffCatalogue,
    private val changes: TariffChanges,
) {
    suspend operator fun invoke(params: Params): Result<TariffChangeView> =
        suspendRunCatching {
            val saga = sagas.findById(params.changeId) ?: throw KonektException.NotFound("tariff change")
            val payload =
                saga.payload as? TariffChangePayload
                    // A saga id that exists and is not a tariff change. NotFound rather than a cast
                    // failure: asking for somebody's purchase by its id must not confirm it exists.
                    ?: throw KonektException.NotFound("tariff change")

            // The owner check, beside the subscriber id the principal carries. `authenticate` proves
            // the caller is somebody and says nothing about whose change this is.
            if (payload.subscriberId != params.subscriberId) throw KonektException.NotFound("tariff change")

            if (saga.status != PetichStatus.PENDING_SIGNATURE) {
                throw KonektException.Conflict("this change is not waiting for a confirmation")
            }

            // RESUMED BY PROCESSING THE SAGA AGAIN with a resume payload on it, which is petich's
            // shape — there is no `resume(id, payload)`. The purchase saga does the same thing, and
            // doing it differently here would be two ways to say one thing.
            engine.process(saga.copy(resumePayload = TariffConfirmation()))

            // The same builder the other two use. It used to be a third construction here with
            // `requiredAction = null` written in — true today, and a value rather than a reading:
            // a confirmation that failed to resume would report itself as needing nothing.
            tariffChangeViewOf(params.changeId, params.subscriberId, sagas, catalogue, changes)
        }

    class Params(
        val changeId: String,
        val subscriberId: String,
    )
}
