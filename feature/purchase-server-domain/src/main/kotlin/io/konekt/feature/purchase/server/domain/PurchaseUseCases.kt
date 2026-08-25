package io.konekt.feature.purchase.server.domain

import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.ResumePayload
import ru.workinprogress.petich.SimpleEnrichedPayload
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The answer to a confirmation. It carries nothing yet and still exists as a type: a resume with no
// payload and a resume that decided something are different requests, and the second one is coming
// (a one-time code, a chosen payment method).
@Serializable
@SerialName("purchase_confirmation")
class PurchaseConfirmation : ResumePayload()

data class OrderView(
    val orderId: String,
    val status: OrderStatus,
    val payload: PurchasePayload,
    val requiredAction: String?,
)

@OptIn(ExperimentalUuidApi::class)
class StartPurchaseUseCase(
    private val engine: PetichEngine,
    private val orders: PetichRepository,
    private val plans: PlanCatalog,
    private val balances: AccountBalances,
) {
    suspend operator fun invoke(params: Params): Result<OrderView> =
        suspendRunCatching {
            val plan = plans.find(params.planId) ?: throw KonektException.NotFound("plan")
            val account = balances.findAccountOf(params.subscriberId) ?: throw KonektException.NotFound("account")

            // The saga id IS the order id. There is no second table holding an order beside the saga
            // that already records every step it took — a duplicate would be a second source of truth
            // for the one thing this product is about.
            val orderId = Uuid.random().toString()

            engine.process(
                Petich(
                    id = orderId,
                    type = PURCHASE_SAGA_TYPE,
                    status = PetichStatus.DRAFT,
                    payload =
                        PurchasePayload(
                            subscriberId = params.subscriberId,
                            accountId = account.id,
                            planId = plan.id,
                            planTitle = plan.title,
                            price = plan.price,
                        ),
                    enrichedPayload = SimpleEnrichedPayload(),
                ),
            )

            // Read back rather than inferred from the engine's answer: that answer says what happened
            // on this pass, and what the client needs is where the order now stands. After a Suspend
            // those differ.
            (orders.findById(orderId) ?: throw KonektException.NotFound("order")).toView()
        }

    data class Params(
        val subscriberId: String,
        val planId: String,
    )
}

class ConfirmPurchaseUseCase(
    private val engine: PetichEngine,
    private val orders: PetichRepository,
) {
    suspend operator fun invoke(params: Params): Result<OrderView> =
        suspendRunCatching {
            val order = orders.findById(params.orderId).ownedByOr404(params.subscriberId)

            if (order.status.isTerminalStatus()) {
                throw KonektException.Conflict("this order has already finished")
            }
            if (order.status != PetichStatus.PENDING_SIGNATURE) {
                throw KonektException.Conflict("this order is not waiting for a confirmation")
            }

            engine.process(order.copy(resumePayload = PurchaseConfirmation()))

            (orders.findById(params.orderId) ?: throw KonektException.NotFound("order")).toView()
        }

    data class Params(
        val orderId: String,
        val subscriberId: String,
    )
}

class FindOrderUseCase(
    private val orders: PetichRepository,
) {
    suspend operator fun invoke(params: Params): Result<OrderView> =
        suspendRunCatching {
            orders.findById(params.orderId).ownedByOr404(params.subscriberId).toView()
        }

    data class Params(
        val orderId: String,
        val subscriberId: String,
    )
}

// 404 AND NOT 403 for somebody else's order, the same rule the routes follow through ownedOr404.
// It is repeated here rather than imported because the helper is Ktor's and this module has no
// business knowing about HTTP — and because the check belongs beside the owner, in the use case,
// rather than in the route. A 403 would confirm that the order exists, which is an enumeration
// oracle for anyone who wants one.
private fun Petich?.ownedByOr404(subscriberId: String): Petich {
    val payload = this?.payload as? PurchasePayload
    if (this == null || payload == null || payload.subscriberId != subscriberId) {
        throw KonektException.NotFound("order")
    }
    return this
}

private fun PetichStatus.isTerminalStatus(): Boolean =
    this == PetichStatus.COMPLETED || this == PetichStatus.REJECTED || this == PetichStatus.FAILED

internal fun Petich.toView(): OrderView =
    OrderView(
        orderId = id,
        status = OrderStatus.of(status),
        payload = payload as PurchasePayload,
        requiredAction = if (status == PetichStatus.PENDING_SIGNATURE) ACTION_CONFIRM else null,
    )
