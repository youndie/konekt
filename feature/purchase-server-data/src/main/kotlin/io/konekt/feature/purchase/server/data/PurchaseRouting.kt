package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.feature.purchase.server.domain.ConfirmPurchaseUseCase
import io.konekt.feature.purchase.server.domain.FindOrderUseCase
import io.konekt.feature.purchase.server.domain.LoadOrderScreenUseCase
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.OrderView
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.http.subscriberId
import io.konekt.money.MoneyFormat
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token, all three. Every route here acts on the caller's own money, and the owner
// check is in the use case beside the subscriber id rather than here — `authenticate` proves the
// caller is somebody, and says nothing about whose order this is.
fun Route.purchaseRoutes() {
    val startPurchase by inject<StartPurchaseUseCase>()
    val confirmPurchase by inject<ConfirmPurchaseUseCase>()
    val findOrder by inject<FindOrderUseCase>()
    val loadOrderScreen by inject<LoadOrderScreenUseCase>()
    val json by inject<Json>()

    post<Purchases> {
        val body = call.receive<CreatePurchaseRequest>()
        val order =
            startPurchase(
                StartPurchaseUseCase.Params(subscriberId = call.subscriberId(), planId = body.planId),
            ).getOrThrow()

        // 202 rather than 201: the usual answer is a saga waiting for a confirmation, and telling a
        // client the resource is created when the money has only been held would be a lie it would
        // then have to unlearn.
        call.respond(HttpStatusCode.Accepted, order.toResponse())
    }

    post<Purchases.ById.Confirm> { params ->
        val order =
            confirmPurchase(
                ConfirmPurchaseUseCase.Params(orderId = params.parent.orderId, subscriberId = call.subscriberId()),
            ).getOrThrow()

        call.respond(order.toResponse())
    }

    get<OrderScreen> { params ->
        val screen =
            loadOrderScreen(
                FindOrderUseCase.Params(orderId = params.orderId, subscriberId = call.subscriberId()),
            ).getOrThrow()

        // respondKompotComponent, never call.respond. A plain respond resolves the serialiser from
        // the concrete runtime class and drops the "type" discriminator on the ROOT of the tree —
        // nested children are unaffected, which is what makes it easy to miss — and the client then
        // receives an unknown component for the whole screen and, by design, draws nothing.
        call.respondKompotComponent(
            json,
            PurchaseResultScreen.build(screen.order, screen.reversal, screen.balance),
        )
    }

    get<Purchases.ById> { params ->
        val order =
            findOrder(
                FindOrderUseCase.Params(orderId = params.orderId, subscriberId = call.subscriberId()),
            ).getOrThrow()

        call.respond(order.toResponse())
    }
}

private fun OrderView.toResponse(): PurchaseOrderResponse =
    PurchaseOrderResponse(
        orderId = orderId,
        status = status.wireName,
        planId = payload.planId,
        // Formatted here, on the server, because that is the only side that can. See MoneyFormat.
        priceText = MoneyFormat.format(payload.price),
        requiredAction = requiredAction,
        // Stated in money, which is what the canvas asks for: what was reversed and what the balance
        // is now, rather than an apology. A subscriber who can reconcile a reversal against their
        // bank does not ring support about it.
        reversalText =
            if (status == OrderStatus.COMPENSATED) {
                // The provider's own words first when there are any, then the money. A subscriber who
                // is told what was reversed and what their balance is now does not ring support; one
                // who is told "something went wrong" does.
                val what = declineReason?.let { "$it " } ?: ""
                "$what${MoneyFormat.format(payload.price)} was returned to your balance and nothing was activated."
            } else {
                null
            },
    )
