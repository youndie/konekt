package io.konekt.screens

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.domain.KonektException
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.http.subscriberId
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token. Every value on this screen is the caller's own, and which caller comes from
// the verified token rather than from anything they sent.
//
// ASSEMBLED IN `:server` like the home screen, and for the same reason: it reads the auth feature's
// subscriber and the eSIM feature's profiles, and a feature reaching into another's repository to
// draw one screen is how two features become one. The composition root composes.
fun Route.profileRoutes() {
    val subscribers by inject<SubscriberRepository>()
    val esims by inject<EsimRepository>()
    val json by inject<Json>()

    get<ProfileScreenResource> {
        val subscriberId = call.subscriberId()
        // A token whose subscriber no longer exists is a 404 rather than an empty screen. It cannot
        // happen today — nothing deletes a subscriber — and saying so costs one line, while the
        // alternative is a screen drawing a blank where a number should be.
        val subscriber =
            subscribers.findById(subscriberId)
                ?: throw KonektException.NotFound("subscriber")

        call.respondKompotComponent(
            json,
            ProfileScreen.build(
                msisdn = subscriber.msisdn.value,
                esimsHeld = esims.countHeldBy(subscriberId),
                nav = Shell.bottomNav(Shell.Tab.PROFILE),
            ),
        )
    }
}
