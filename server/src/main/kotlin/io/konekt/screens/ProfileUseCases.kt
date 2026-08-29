package io.konekt.screens

import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.tariff.TariffCatalogue
import io.konekt.tariff.TariffChanges
import kotlin.time.Instant

// THE ACCOUNT SCREEN AS A SET OF ANSWERS, before anything decides how to draw them — the first
// vertical done the way `B-96` says every screen should be.
//
// WHAT IS IN HERE IS WHAT THE SCREEN DECIDES ON; what is not is how it looks. The distinction is
// worth stating because it is easy to get backwards: `tariffTitle` is a TITLE and not an id, because
// resolving an id against a catalogue is a lookup and a renderer that can look things up is a
// renderer that can ask a different question than the one the use case answered. `effectiveAt` is an
// `Instant` and not a formatted day, because formatting is drawing, and a view that carried
// "12 September" could not be compared in a test without agreeing on a date format first.
data class ProfileView(
    val msisdn: String,
    val esims: EsimHoldings,
    val tariffTitle: String,
    // The change already asked for and not yet confirmed, which is the ordinary case for it to be
    // absent. It belongs on this screen rather than only on the catalogue: a subscriber who started
    // something and closed the application looks for it where they look for what they are on.
    val pendingChange: PendingTariffChange? = null,
)

// RESOLVED, both halves. The record carries a tariff id and an instant; a screen given those would
// have to reach for the catalogue to say a name, which is the lookup this whole shape removes.
data class PendingTariffChange(
    val toTariffTitle: String,
    val effectiveAt: Instant,
)

// ASSEMBLED IN `:server` and not in a feature, for the reason the screen itself was: it reads the
// auth feature's subscriber, the eSIM feature's holdings and the tariff catalogue, and a feature
// reaching into another's repository is how two features become one. The composition root composes —
// and this use case IS that composition, moved off the route.
class ViewProfileUseCase(
    private val subscribers: SubscriberRepository,
    private val esims: EsimRepository,
    private val tariffs: TariffCatalogue,
    private val changes: TariffChanges,
) {
    suspend operator fun invoke(subscriberId: String): Result<ProfileView> =
        suspendRunCatching {
            // A token whose subscriber no longer exists is a 404 rather than an empty screen. It
            // cannot happen today — nothing deletes a subscriber — and saying so costs one line,
            // while the alternative is a screen drawing a blank where a number should be.
            val subscriber =
                subscribers.findById(subscriberId)
                    ?: throw KonektException.NotFound("subscriber")

            val currentTariffId = changes.currentTariffId(subscriberId) ?: tariffs.default.id

            ProfileView(
                msisdn = subscriber.msisdn.value,
                esims = esims.holdingsOf(subscriberId),
                // The catalogue's TITLE and not its id. An id on a screen is a value that leaked out
                // of a table; `tr-standard` is not what a subscriber calls anything. Falling back to
                // the id is deliberate: a tariff the catalogue has forgotten is still what they are
                // on, and printing nothing there would be worse than printing a key.
                tariffTitle = titleOf(currentTariffId),
                pendingChange =
                    changes.pendingOf(subscriberId)?.let {
                        PendingTariffChange(titleOf(it.toTariffId), it.effectiveAt)
                    },
            )
        }

    private fun titleOf(tariffId: String): String = tariffs.find(tariffId)?.title ?: tariffId
}
