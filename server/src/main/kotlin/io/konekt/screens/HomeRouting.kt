package io.konekt.screens

import io.github.youndie.kompot.ktor.respondKompotComponent
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.LoadCountersUseCase
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.konekt.http.subscriberId
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
    val json by inject<Json>()

    get<HomeScreenResource> {
        val subscriberId = call.subscriberId()
        val counters = loadCounters(subscriberId).getOrThrow()
        val balance = balances.findAccountOf(subscriberId)?.balance

        // respondKompotComponent, never call.respond. A plain respond resolves the serialiser from
        // the concrete runtime class and drops the "type" discriminator on the ROOT of the tree —
        // nested children serialise perfectly, which is what makes it easy to miss — and the client
        // then receives an unknown component for the whole screen and, by design, draws nothing.
        // `CallRespondUsageTest` is what refuses the other spelling in the sources.
        call.respondKompotComponent(json, HomeScreen.build(balance, counters, cards))
    }
}
