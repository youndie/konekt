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
    val effectiveAt: Instant,
    val requiredAction: String?,
)

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
    ): TariffChangeView {
        // Read back rather than inferred from the engine's answer: that answer says what happened on
        // this pass, and what the client needs is where the change now stands. After a Suspend those
        // differ.
        val saga = sagas.findById(changeId) ?: throw KonektException.NotFound("tariff change")
        val payload = saga.payload as TariffChangePayload

        return TariffChangeView(
            changeId = changeId,
            status = OrderStatus.of(saga.status),
            // The CURRENT tariff, read after the saga ran: applied while it is still pending, and the
            // new one only once the boundary row says applied.
            currentTariffId = changes.currentTariffId(subscriberId) ?: catalogue.default.id,
            requestedTariffId = payload.toTariffId,
            effectiveAt = Instant.fromEpochMilliseconds(payload.effectiveAt),
            requiredAction = if (saga.status == PetichStatus.PENDING_SIGNATURE) ACTION_CONFIRM_TARIFF else null,
        )
    }

    class Params(
        val subscriberId: String,
        val tariffId: String,
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

            val settled = sagas.findById(params.changeId) ?: throw KonektException.NotFound("tariff change")
            TariffChangeView(
                changeId = params.changeId,
                status = OrderStatus.of(settled.status),
                currentTariffId = changes.currentTariffId(payload.subscriberId) ?: catalogue.default.id,
                requestedTariffId = payload.toTariffId,
                effectiveAt = Instant.fromEpochMilliseconds(payload.effectiveAt),
                requiredAction = null,
            )
        }

    class Params(
        val changeId: String,
        val subscriberId: String,
    )
}
