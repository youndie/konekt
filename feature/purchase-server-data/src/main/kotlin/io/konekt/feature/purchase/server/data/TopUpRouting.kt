package io.konekt.feature.purchase.server.data

import io.konekt.feature.purchase.server.domain.FindTopUpUseCase
import io.konekt.feature.purchase.server.domain.StartTopUpUseCase
import io.konekt.feature.purchase.server.domain.TopUpAmount
import io.konekt.feature.purchase.server.domain.TopUpView
import io.konekt.feature.purchase.shared.api.CreateTopUpRequest
import io.konekt.feature.purchase.shared.api.TopUpResponse
import io.konekt.feature.purchase.shared.api.TopUps
import io.konekt.http.subscriberId
import io.konekt.money.MoneyFormat
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject

// AUTH TIER: user token, both. A top-up moves money onto the caller's own account, and the owner
// check is in the use case beside the subscriber id the principal carries — `authenticate` proves the
// caller is somebody and says nothing about whose top-up this is.
fun Route.topUpRoutes() {
    val startTopUp by inject<StartTopUpUseCase>()
    val findTopUp by inject<FindTopUpUseCase>()

    post<TopUps> {
        val request = call.receive<CreateTopUpRequest>()
        val view =
            startTopUp(
                StartTopUpUseCase.Params(
                    subscriberId = call.subscriberId(),
                    // The DTO endpoint speaks the domain's unit, and now says so at the call site.
                    amount = TopUpAmount.minor(request.amountMinor),
                ),
            ).getOrThrow()

        // 201 and not 202, which is the opposite of the purchase route's answer and for the reason
        // that route gives: a purchase usually ends the request still waiting for a confirmation, so
        // calling it created would be a lie the client has to unlearn. A top-up has no wait — by the
        // time this responds the provider has either taken the money or refused — so the resource
        // really is created, including on the refused branch, where what exists is a refusal with a
        // reason.
        call.respond(HttpStatusCode.Created, view.toResponse())
    }

    get<TopUps.ById> { params ->
        call.respond(
            findTopUp(
                FindTopUpUseCase.Params(topUpId = params.topUpId, subscriberId = call.subscriberId()),
            ).getOrThrow().toResponse(),
        )
    }
}

// Formatted HERE and nowhere else. The server builds the screen, so the client renders text and
// cannot format money inconsistently (D15) — and a top-up and a purchase must not disagree about how
// the same amount looks.
private fun TopUpView.toResponse() =
    TopUpResponse(
        topUpId = id,
        status = status.wireName,
        amountText = MoneyFormat.format(amount),
        balanceText = MoneyFormat.format(balance),
        declineReason = declineReason,
    )
