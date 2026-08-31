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
// THE TWO SCREEN ADDRESSES AND THE DEEPLINK WENT THE SAME WAY (`B-102`): `/screens/tariffs`,
// `/screens/tariff-changes/{changeId}` and `app://tariffs` described a catalogue and a change screen
// the server no longer serves, and a `@Resource` for an address nothing answers is a 404 waiting for
// whoever believes this file.
//
// THE TWO ACTIONS WENT WITH THE SCREENS THAT SENT THEM (`B-102`). `change_tariff` and
// `confirm_tariff_change` existed so a tariff catalogue and a change screen could press for a saga
// this build never billed for — and with those screens gone nothing composes either action, so
// keeping them would be wire vocabulary nobody speaks: declared, plausible, and never sent, which is
// exactly the shape the paragraph above refuses.
//
// The saga did not go with them. It is driven over the DTO routes this file still declares, and
// `TariffChangeScenarioTest` is what exercises the suspend, the confirmation and the boundary.
