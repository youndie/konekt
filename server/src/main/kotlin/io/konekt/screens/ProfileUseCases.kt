package io.konekt.screens

import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.tariff.PendingTariffChange
import io.konekt.tariff.TariffCatalogue
import io.konekt.tariff.TariffChanges
import io.konekt.tariff.pendingTariffChangeOf
import io.konekt.tariff.titleOf

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

            ProfileView(
                msisdn = subscriber.msisdn.value,
                esims = esims.holdingsOf(subscriberId),
                // The catalogue's TITLE and not its id, through the same `titleOf` the tariff
                // screens use. An id on a screen is a value that leaked out of a table, and
                // `tr-standard` is not what a subscriber calls anything.
                tariffTitle = tariffs.titleOf(changes.currentTariffId(subscriberId) ?: tariffs.default.id),
                // THROUGH THE SHARED RESOLVER, so the profile and the tariff catalogue cannot come to
                // describe one waiting change differently — which is what they were doing, in two
                // places, one of them a routing file.
                pendingChange = pendingTariffChangeOf(subscriberId, changes, tariffs),
            )
        }
}
