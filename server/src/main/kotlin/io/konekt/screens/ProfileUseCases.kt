package io.konekt.screens

import io.konekt.domain.KonektException
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.server.domain.EsimRepository

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
)

// ASSEMBLED IN `:server` and not in a feature, for the reason the screen itself was: it reads the
// auth feature's subscriber, the eSIM feature's holdings and the tariff catalogue, and a feature
// reaching into another's repository is how two features become one. The composition root composes —
// and this use case IS that composition, moved off the route.
class ViewProfileUseCase(
    private val subscribers: SubscriberRepository,
    private val esims: EsimRepository,
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
            )
        }
}
