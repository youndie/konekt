package io.konekt.topup

import io.github.youndie.kompot.form.FieldValue
import io.github.youndie.kompot.form.standard.AmountValue
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.ktor.respondKompotAction
import io.github.youndie.kompot.ktor.respondKompotComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.domain.KonektException
import io.konekt.feature.purchase.server.domain.FindTopUpUseCase
import io.konekt.feature.purchase.server.domain.StartTopUpUseCase
import io.konekt.feature.purchase.server.domain.TopUpAmount
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import io.konekt.feature.purchase.shared.api.TopUpForms
import io.konekt.feature.purchase.shared.api.TopUpScreenResource
import io.konekt.http.subscriberId
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token, all three. A top-up moves money onto the caller's own account and the owner
// check lives in the use cases beside the principal — `authenticate` proves the caller is somebody
// and says nothing about whose top-up this is.
//
// THE SUBMIT IS A POST ON THE SCREEN'S OWN RESOURCE rather than a path of its own, so it cannot
// collide with the result address below: a sibling named `submit` is one `topUpId` away from being
// unreachable.
fun Route.topUpScreenRoutes() {
    val startTopUp by inject<StartTopUpUseCase>()
    val findTopUp by inject<FindTopUpUseCase>()
    val json by inject<Json>()

    get<TopUpScreenResource> {
        call.respondForm(json, TopUpScreens.amount())
    }

    post<TopUpScreenResource> {
        val amount =
            call.amountValues(json)[TopUpForms.FIELD_AMOUNT].let { it as? AmountValue }
                ?: throw KonektException.Validation(TopUpForms.FIELD_AMOUNT, "no amount")

        // REFUSED IS AN ANSWER, NOT A FAILURE, and the distinction decides what a subscriber sees.
        // The use case returns a view for a refusal it can describe — out of range, provider
        // declined — so throwing would give a 422 and a screen that did not change. A refusal it
        // cannot describe is still an exception and StatusPages still maps it.
        // WHOLE UNITS, because that is what the field holds. kompot's amount input filters the
        // keystrokes to digits and hands back the integer it is displaying, so the number here is the
        // number under the `$` on the subscriber's screen. Handing it to a parameter called
        // `amountMinor` — which is what this line used to do — credited a hundredth of it: typing
        // 5000 added $50, and typing 50 was refused by the screen that had just named $10 as the
        // minimum (`B-67`).
        //
        // `AmountValue` also carries a currency, and it is deliberately not read: the use case takes
        // the currency from the ACCOUNT, and the conversion to minor units happens there, where the
        // exponent is known.
        val view =
            startTopUp(
                StartTopUpUseCase.Params(call.subscriberId(), TopUpAmount.whole(amount.long)),
            ).getOrThrow()

        // A `navigate` and not the result's tree: the endpoint says WHERE, the client fetches, and
        // arriving at a screen has one shape rather than two.
        call.respondKompotAction(json, NavigateAction("$TOP_UP_DEEPLINK/${view.id}"))
    }

    get<TopUpScreenResource.ById> { params ->
        val view =
            findTopUp(
                FindTopUpUseCase.Params(topUpId = params.topUpId, subscriberId = call.subscriberId()),
            ).getOrThrow()

        // `respondKompotComponent`, never `call.respond`: a plain respond resolves the serialiser
        // from the concrete class and drops the discriminator on the ROOT of the tree, so the client
        // receives an unknown component for the whole screen.
        call.respondKompotComponent(json, TopUpScreens.result(view))
    }
}

// The form's values as a `submit_form` posts them — the same `FormPatchRequest` shape a patch uses.
// Read as text rather than through ContentNegotiation because the polymorphic scope that decodes a
// `FieldValue` is the application's `Json`, and `AmountValue` is one of its subclasses.
private suspend fun ApplicationCall.amountValues(json: Json): Map<String, FieldValue> =
    json.decodeFromString(FormPatchRequest.serializer(), receiveText()).values

private suspend fun ApplicationCall.respondForm(
    json: Json,
    form: KompotFormResponse,
) = respondText(
    json.encodeToString(KompotFormResponse.serializer(), form),
    ContentType.Application.Json,
)
