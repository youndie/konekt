package io.konekt.packages

import io.konekt.domain.KonektException
import io.konekt.feature.packages.shared.api.CustomPackageFields
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.http.subscriberId
import io.ktor.http.ContentType
import io.ktor.server.resources.get
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import io.konekt.feature.packages.shared.api.CustomPackageForm as CustomPackageFormResource

// AUTH TIER: user token. The form shows the caller's own balance and prices against it, so which
// subscriber comes from the verified token rather than from anything the caller sent.
fun Route.customPackageRoutes() {
    val balances by inject<AccountBalances>()
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
