package io.konekt.packages

import io.github.youndie.kompot.form.FormPatch
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.ktor.respondKompotAction
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.domain.KonektException
import io.konekt.feature.packages.shared.api.CustomPackageFields
import io.konekt.feature.packages.shared.api.CustomPackagePatch
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.shared.api.ORDER_DEEPLINK
import io.konekt.http.subscriberId
import io.ktor.http.ContentType
import io.ktor.server.request.receiveText
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import io.konekt.feature.packages.shared.api.CustomPackageForm as CustomPackageFormResource

// AUTH TIER: user token. The form shows the caller's own balance and prices against it, so which
// subscriber comes from the verified token rather than from anything the caller sent.
fun Route.customPackageRoutes() {
    val balances by inject<AccountBalances>()
    val startPurchase by inject<StartPurchaseUseCase>()
    val json by inject<Json>()

    get<CustomPackageFormResource> { params ->
        val balance =
            balances.findAccountOf(call.subscriberId())?.balance
                ?: throw KonektException.NotFound("account")

        val quantities =
            CustomPackageQuantities(
                dataGb = params.dataGb.asStep(CustomPackageFields.DATA_GB, CustomPackageTariff.DATA_GB_STEPS),
                minutes = params.minutes.asStep(CustomPackageFields.MINUTES, CustomPackageTariff.MINUTES_STEPS),
                messages = params.messages.asStep(CustomPackageFields.MESSAGES, CustomPackageTariff.MESSAGES_STEPS),
            )
        val price = CustomPackageTariff.priceOf(quantities.dataGb, quantities.minutes, quantities.messages)

        // ENCODED WITH THE APPLICATION'S `json`, never a plain respond: a `KompotFormResponse` carries
        // a component tree, and ContentNegotiation's Json has none of this build's dictionary in its
        // polymorphic scope. The same defect the history page shipped with for a week.
        call.respondText(
            json.encodeToString(CustomPackageForm.response(balance, price)),
            ContentType.Application.Json,
        )
    }

    // THE SUBMIT, and its absence is what `B-87` found: the form could answer a price and had no way
    // to say yes, so its terminal state was a number. A POST on the SCREEN'S OWN RESOURCE rather than
    // a path of its own — the same arrangement the top-up form uses, and for the same reason: a
    // sibling named `submit` is one parameter away from colliding with something.
    //
    // THE PRICE IS COMPUTED HERE AND IS NOT CARRIED FROM THE LAST PATCH. The request brings three
    // quantities; the price comes from the tariff function, on this side, exactly as it did on every
    // patch. A price the client sends back is a price the client can argue with, which is the reason
    // `operator-boundaries` gives for never handing over the table.
    post<CustomPackageFormResource> {
        val request = json.decodeFromString(FormPatchRequest.serializer(), call.receiveText())
        val quantities =
            CustomPackageQuantities(
                dataGb = request.step(CustomPackageFields.DATA_GB, CustomPackageTariff.DATA_GB_STEPS),
                minutes = request.step(CustomPackageFields.MINUTES, CustomPackageTariff.MINUTES_STEPS),
                messages = request.step(CustomPackageFields.MESSAGES, CustomPackageTariff.MESSAGES_STEPS),
            )
        CustomPackagePlans.requireSomethingChosen(quantities)

        // INTO THE SAME SAGA AS ANY OTHER PURCHASE. The id encodes the quantities and the catalogue
        // resolves it, so the interceptors — the balance check, the hold, the settlement, the
        // entitlement, the compensation — do not know a custom package from a listed one. Every
        // refusal and every screen the purchase already has works here unchanged, including the
        // insufficient-balance one, which is why this route does not check the balance itself.
        val view =
            startPurchase(
                StartPurchaseUseCase.Params(
                    subscriberId = call.subscriberId(),
                    planId = CustomPackagePlans.idOf(quantities.dataGb, quantities.minutes, quantities.messages),
                ),
            ).getOrThrow()

        // A `navigate` and not the order's tree: the endpoint says WHERE and the client fetches, so
        // arriving at a screen has one shape rather than two. It is the ORDER screen, the same one a
        // catalogue plan lands on — including its refused and compensated branches.
        call.respondKompotAction(json, NavigateAction("$ORDER_DEEPLINK/${view.orderId}"))
    }

    // THE PATCH. A quantity moved; the price and the balance come back and nothing is redrawn.
    //
    // It reads the quantities out of the request's VALUES rather than out of a query, because that is
    // what a `FormController` sends: the whole form as the client currently holds it. The two computed
    // fields arrive in that snapshot too and are ignored — what the client believes the price to be is
    // not an input to computing it.
    post<CustomPackagePatch> {
        val balance =
            balances.findAccountOf(call.subscriberId())?.balance
                ?: throw KonektException.NotFound("account")

        val request = json.decodeFromString(FormPatchRequest.serializer(), call.receiveText())
        val quantities =
            CustomPackageQuantities(
                dataGb = request.step(CustomPackageFields.DATA_GB, CustomPackageTariff.DATA_GB_STEPS),
                minutes = request.step(CustomPackageFields.MINUTES, CustomPackageTariff.MINUTES_STEPS),
                messages = request.step(CustomPackageFields.MESSAGES, CustomPackageTariff.MESSAGES_STEPS),
            )
        val price = CustomPackageTariff.priceOf(quantities.dataGb, quantities.minutes, quantities.messages)

        call.respondText(
            json.encodeToString(FormPatch.serializer(), CustomPackageForm.patch(balance, price)),
            ContentType.Application.Json,
        )
    }
}

// One field of a patch request as a quantity. `plainValue` rather than a cast to `EntityValue`: the
// wire says what a value is and this only needs the string it renders as, so a client sending the
// same number as text is understood rather than refused for its packaging.
//
// Absent is the first step, the same rule the GET follows — a form nobody has touched sends nothing.
private fun FormPatchRequest.step(
    fieldId: String,
    steps: List<Long>,
): Long {
    val raw = values[fieldId]?.plainValue ?: return steps.first()
    val chosen =
        raw.toLongOrNull()
            ?: throw KonektException.Validation(fieldId, "\"$raw\" is not a quantity")
    if (!CustomPackageTariff.isStep(chosen, steps)) {
        throw KonektException.Validation(fieldId, "$chosen is not one of the sizes this package comes in")
    }
    return chosen
}

// A QUANTITY OUTSIDE THE STEPS IS REFUSED, not rounded. Rounding would charge a subscriber for a
// package they did not choose, and the client picks from the same list the server prices — so
// anything else arrived from something that is not this form.
//
// Absent is the first step: a form nobody has touched yet sends nothing, and demanding all three
// would be a form that cannot be opened.
private fun Long?.asStep(
    fieldId: String,
    steps: List<Long>,
): Long {
    val chosen = this ?: return steps.first()
    if (!CustomPackageTariff.isStep(chosen, steps)) {
        throw KonektException.Validation(fieldId, "$chosen is not one of the sizes this package comes in")
    }
    return chosen
}
