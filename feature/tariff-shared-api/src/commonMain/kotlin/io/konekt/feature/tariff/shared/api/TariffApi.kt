package io.konekt.feature.tariff.shared.api

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// CHANGING TARIFF, as the second saga in this build — and the second one with a confirmation, which
// is the point: a tariff change is exactly the sort of thing a subscriber should be asked twice about.
@Resource("/api/v1/tariff-changes")
class TariffChanges {
    @Resource("{changeId}")
    class ById(
        val parent: TariffChanges = TariffChanges(),
        val changeId: String,
    ) {
        // The confirmation the saga is waiting for. A separate request rather than a parameter of the
        // first, because the wait is the point: the saga holds nothing open while it waits.
        @Resource("confirm")
        class Confirm(
            val parent: ById,
        )
    }
}

@Serializable
data class ChangeTariffRequest(
    val tariffId: String,
)

// A tariff change as a subscriber sees it.
//
// `currentTariffId` is here beside the requested one deliberately: the acceptance this feature was
// written for is that a confirmed change shows the new tariff AND the old one still current, because
// a change that takes effect on a boundary means both are true until that date.
@Serializable
data class TariffChangeResponse(
    val changeId: String,
    val status: String,
    val currentTariffId: String,
    val requestedTariffId: String,
    // Formatted by the server, like every other date and amount here: the client renders text and
    // cannot format inconsistently.
    val effectiveOnText: String,
    val requiredAction: String? = null,
    val declineReason: String? = null,
)
