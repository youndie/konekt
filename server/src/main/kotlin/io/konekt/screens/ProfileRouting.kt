package io.konekt.screens

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.domain.KonektException
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.http.subscriberId
import io.konekt.money.DayFormat
import io.konekt.tariff.TariffCatalogue
import io.konekt.tariff.TariffChanges
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
    // THE TARIFF HALVES, read here rather than inside `ProfileScreen`. The screen composes text and
    // knows no repository — the composition root composes, which is the same rule that puts the eSIM
    // holdings on this line.
    val tariffs by inject<TariffCatalogue>()
    val tariffChanges by inject<TariffChanges>()
    val json by inject<Json>()

    get<ProfileScreenResource> {
        val subscriberId = call.subscriberId()
        // A token whose subscriber no longer exists is a 404 rather than an empty screen. It cannot
        // happen today — nothing deletes a subscriber — and saying so costs one line, while the
        // alternative is a screen drawing a blank where a number should be.
        val subscriber =
            subscribers.findById(subscriberId)
                ?: throw KonektException.NotFound("subscriber")

        val currentTariffId = tariffChanges.currentTariffId(subscriberId) ?: tariffs.default.id
        val pending = tariffChanges.pendingOf(subscriberId)

        call.respondKompotComponent(
            json,
            ProfileScreen.build(
                msisdn = subscriber.msisdn.value,
                esims = esims.holdingsOf(subscriberId),
                // The catalogue's TITLE and not its id. An id on a screen is a value that leaked out
                // of a table; `tr-standard` is not what a subscriber calls anything.
                tariffTitle = tariffs.find(currentTariffId)?.title ?: currentTariffId,
                pendingTariffText =
                    pending?.let {
                        val to = tariffs.find(it.toTariffId)?.title ?: it.toTariffId
                        "A change to $to is waiting for your confirmation, and takes effect on " +
                            "${DayFormat.dayAndMonth(it.effectiveAt)}."
                    },
                nav = Shell.bottomNav(Shell.Tab.PROFILE),
            ),
        )
    }
}
