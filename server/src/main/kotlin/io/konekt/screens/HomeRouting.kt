package io.konekt.screens

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.LoadCountersUseCase
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.konekt.http.subscriberId
import io.konekt.roaming.RoamingPackageCards
import io.konekt.theme.BrandThemeCatalogue
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: user token. The screen shows one subscriber's money and one subscriber's allowance, and
// which subscriber comes from the verified token rather than from anything the caller sent.
fun Route.homeRoutes() {
    val loadCounters by inject<LoadCountersUseCase>()
    val balances by inject<AccountBalances>()
    val cards by inject<UsageCounterCards>()
    val roaming by inject<RoamingPackages>()
    val roamingCards by inject<RoamingPackageCards>()
    val subscribers by inject<SubscriberRepository>()
    // Whether this line holds a profile yet, which decides whether the install door is drawn.
    val esims by inject<EsimRepository>()
    // The brand kit this deployment serves, for the one string on this screen that is a fact
    // about the DEPLOYMENT rather than about the subscriber.
    val brand by inject<BrandThemeCatalogue>()
    val json by inject<Json>()

    get<HomeScreenResource> {
        val subscriberId = call.subscriberId()
        val counters = loadCounters(subscriberId).getOrThrow()
        val balance = balances.findAccountOf(subscriberId)?.balance
        val packages = roaming.of(subscriberId)

        // respondKompotComponent, never call.respond. A plain respond resolves the serialiser from
        // the concrete runtime class and drops the "type" discriminator on the ROOT of the tree —
        // nested children serialise perfectly, which is what makes it easy to miss — and the client
        // then receives an unknown component for the whole screen and, by design, draws nothing.
        // `CallRespondUsageTest` is what refuses the other spelling in the sources.
        call.respondKompotComponent(
            json,
            HomeScreen.build(
                msisdn = subscribers.findById(subscriberId)?.msisdn?.value,
                balance = balance,
                counters = counters,
                cards = cards,
                packages = packages,
                roamingCards = roamingCards,
                brandName = brand.displayName,
                esims = esims.holdingsOf(subscriberId),
                nav = Shell.bottomNav(Shell.Tab.HOME),
            ),
        )
    }
}
