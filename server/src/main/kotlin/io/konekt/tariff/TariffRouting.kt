package io.konekt.tariff

import io.konekt.feature.tariff.shared.api.ChangeTariffRequest
import io.konekt.feature.tariff.shared.api.TariffChangeResponse
import io.konekt.http.subscriberId
import io.konekt.money.DayFormat
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject
import io.konekt.feature.tariff.shared.api.TariffChanges as TariffChangesResource

// AUTH TIER: user token, both. A change moves the caller's own tariff, and the owner check lives in
// the use case beside the subscriber id rather than here.
fun Route.tariffRoutes() {
    val startChange by inject<StartTariffChangeUseCase>()
    val confirmChange by inject<ConfirmTariffChangeUseCase>()

    post<TariffChangesResource> {
        val body = call.receive<ChangeTariffRequest>()
        val view =
            startChange(
                StartTariffChangeUseCase.Params(subscriberId = call.subscriberId(), tariffId = body.tariffId),
            ).getOrThrow()

        // 202, like the purchase: the usual answer is a saga waiting for a confirmation, and telling a
        // client the resource is created when nothing has been decided is a lie it would have to
        // unlearn.
        call.respond(HttpStatusCode.Accepted, view.toResponse())
    }

    post<TariffChangesResource.ById.Confirm> { params ->
        call.respond(
            confirmChange(
                ConfirmTariffChangeUseCase.Params(
                    changeId = params.parent.changeId,
                    subscriberId = call.subscriberId(),
                ),
            ).getOrThrow().toResponse(),
        )
    }
}

// Formatted here and nowhere else. The date is the server's for the same reason money is: the client
// renders text and cannot get it wrong twice — and the zone is the operator's, which `DayFormat`
// states as a real limitation rather than a simplification.
private fun TariffChangeView.toResponse() =
    TariffChangeResponse(
        changeId = changeId,
        status = status.wireName,
        currentTariffId = currentTariffId,
        requestedTariffId = requestedTariffId,
        effectiveOnText = DayFormat.dayAndMonth(effectiveAt),
        requiredAction = requiredAction,
    )
