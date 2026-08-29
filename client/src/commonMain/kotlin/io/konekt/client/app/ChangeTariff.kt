package io.konekt.client.app

import io.github.youndie.kompot.KompotAction
import io.konekt.feature.tariff.shared.api.ChangeTariffAction
import io.konekt.feature.tariff.shared.api.ChangeTariffRequest
import io.konekt.feature.tariff.shared.api.ConfirmTariffChangeAction
import io.konekt.feature.tariff.shared.api.TariffChangeResponse
import io.konekt.feature.tariff.shared.api.TariffChangeScreenResource
import io.konekt.feature.tariff.shared.api.TariffChanges
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.serialization.serializer

// CHANGING TARIFF, handled by the composition root and not by the holder — the same arrangement
// `BuyPlan` has and for the same reason: a holder that posted to `/api/v1/tariff-changes` would be a
// screen holder with an opinion about the product.
//
// THE SECOND SAGA, and the one that is not about money arriving. A purchase asks "spend this?"; a
// tariff change asks "change what you are on?" — a different refusal, a different reversal, and the
// second is what shows petich's suspend/resume is not being used for one shape of transaction only.
// Until `B-86` it was demonstrated only in a harness where confirming was a function call.
class ChangeTariff(
    private val http: HttpClient,
) {
    // Returns where to go next, or null when the action belongs to somebody else in the chain. Null
    // rather than an exception, like every other handler here.
    suspend fun addressFor(action: KompotAction): String? =
        when (action) {
            is ChangeTariffAction -> changeScreen(start(action.tariffId))

            // CONFIRMING LANDS HERE because it is the second half of the same verb — and it is also
            // what the "review it" control on the catalogue sends, which is a change that already
            // exists and is only being looked at. Both end on the same screen; what differs is which
            // state that screen is in.
            is ConfirmTariffChangeAction -> changeScreen(confirm(action.changeId))

            else -> null
        }

    // 202 AND NOT 201, like the purchase: the usual answer is a saga waiting for a confirmation. What
    // comes back is a change that exists and is not decided, which is exactly what its screen is for.
    private suspend fun start(tariffId: String): TariffChangeResponse =
        http
            .post(TariffChanges()) { setBody(ChangeTariffRequest(tariffId)) }
            .body()

    private suspend fun confirm(changeId: String): TariffChangeResponse =
        http
            .post(TariffChanges.ById.Confirm(parent = TariffChanges.ById(changeId = changeId)))
            .body()

    private fun changeScreen(change: TariffChangeResponse): String =
        ResourcesFormat()
            .encodeToPathPattern(serializer<TariffChangeScreenResource>())
            // The pattern carries the placeholder; the id is the one thing this client fills in.
            // Reading the pattern rather than typing the path keeps the ADDRESS spelled once, in the
            // annotation, the way every other address in this client is.
            .replace("{changeId}", change.changeId)
            .let { if (it.startsWith("/")) it else "/$it" }
}
