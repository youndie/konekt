package io.konekt.feature.tariff.shared.api

import io.github.youndie.kompot.KompotAction
import io.ktor.resources.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

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

// THE SCREENS, WHICH THIS VERTICAL HAD NONE OF UNTIL `B-86`.
//
// The saga, the table, the confirmation and the routes above were all built by `B-21` and their only
// caller was an end-to-end test: no component anywhere sent a `ChangeTariffRequest`, `:client` did
// not even depend on this module, and three tariffs sat in a catalogue nothing displayed. A vertical
// whose only user is a test demonstrates the engine and not the product.
@Resource("/api/v1/screens/tariffs")
class TariffsScreenResource

// ONE CHANGE, addressed. The catalogue is a tab-less screen reached from the profile; this is where a
// requested change waits for its confirmation and where its outcome is read afterwards.
@Resource("/api/v1/screens/tariff-changes/{changeId}")
class TariffChangeScreenResource(
    val changeId: String,
)

// `app://tariffs` — the catalogue. Reached from the profile screen, which is where a subscriber looks
// for what they are on.
const val TARIFFS_DEEPLINK: String = "app://tariffs"

// NO DEEPLINK FOR ONE CHANGE, and the absence is deliberate. `app://order` exists because the HISTORY
// screen sends a `navigate` to a specific order; nothing ever navigates to a tariff change — it is
// reached by an ACTION whose answer carries the id, and the client builds the address from the
// resource above. A constant written here and used by nothing would be the shape this repository
// files as a defect: declared, plausible, and never called.

// ASKING FOR A TARIFF, as an action rather than a `navigate`. The distinction is the same one buying
// makes: a `navigate` goes to an address known in advance, and where this ends up is a change that
// does not exist until the press — its id is the answer, not the request.
@Serializable
@SerialName("change_tariff")
data class ChangeTariffAction(
    val tariffId: String,
) : KompotAction

// THE SECOND HALF OF THE SAME VERB. The saga suspends after the change is requested and does nothing
// at all until this arrives — which is the case petich's suspend/resume exists for, and the one this
// build could previously only demonstrate in a harness where the confirmation was a function call.
@Serializable
@SerialName("confirm_tariff_change")
data class ConfirmTariffChangeAction(
    val changeId: String,
) : KompotAction

// Registered by hand on both sides, like every other action here. Nothing fails at build time if one
// side omits it; the press simply cannot be decoded, and `konektActionWireNames` is what makes that
// a failing test rather than a 500 on a screen somebody is looking at.
val tariffActionsSerializersModule =
    SerializersModule {
        polymorphic(KompotAction::class) {
            subclass(ChangeTariffAction::class)
            subclass(ConfirmTariffChangeAction::class)
        }
    }
