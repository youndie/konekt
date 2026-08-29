package io.konekt.screens

import io.konekt.domain.Money
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.usage.server.domain.LoadCountersUseCase
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.theme.BrandThemeCatalogue
import io.konekt.time.KonektClock
import kotlin.time.Instant

// THE FIRST SCREEN'S ANSWERS, out of four repositories and a brand kit, before anything draws
// (`B-96`).
//
// It is the screen that proves the loop — the server builds a tree, the client renders it, nothing
// about the layout lives on the client — and it was also the screen assembled most widely: the route
// injected six things and `HomeScreen.build` took eight parameters, two of which were injected
// renderers rather than data.
//
// AND ONE `now` FOR ALL OF IT. Both card factories used to hold a clock and read it per card, so a
// screen with three counters and two travel packages could caption five cards against five instants.
// Nothing would ever have shown it; it is the sort of thing that becomes a defect only once, in the
// one report nobody can reproduce.
data class HomeView(
    val at: Instant,
    // WHAT THIS DEPLOYMENT IS CALLED, from the served brand kit. `null` draws no header at all: a
    // white-label product that guessed a name would print the wrong operator's name on the operator's
    // own screen, which is worse than printing none.
    val brandName: String?,
    // WHOSE LINE THIS IS. Nullable because the screen is worth drawing without it: a number the
    // server could not read is left out rather than drawn as a blank, for the same reason the balance
    // is.
    val msisdn: String?,
    val balance: Money?,
    val counters: List<UsageCounter>,
    // Roaming packages sit on the SAME screen as the home counters rather than behind a tab, because
    // from the subscriber's side they are one question — what have I got.
    val packages: List<RoamingPackage>,
    // WHAT THIS LINE HOLDS, split by whether anything is on a device. It was one count — profiles
    // held — and the profile screen read the same number under the word "installed"; two screens
    // disagreeing about one question is exactly what that shape produced (`B-69`).
    val esims: EsimHoldings,
)

class ViewHomeUseCase(
    private val loadCounters: LoadCountersUseCase,
    private val balances: AccountBalances,
    private val roaming: RoamingPackages,
    private val subscribers: SubscriberRepository,
    private val esims: EsimRepository,
    private val brand: BrandThemeCatalogue,
    private val clock: KonektClock,
) {
    suspend operator fun invoke(subscriberId: String): Result<HomeView> =
        suspendRunCatching {
            HomeView(
                at = clock.now(),
                // A fact about the DEPLOYMENT rather than about the subscriber, and the only one on
                // this screen.
                brandName = brand.displayName,
                msisdn = subscribers.findById(subscriberId)?.msisdn?.value,
                balance = balances.findAccountOf(subscriberId)?.balance,
                counters = loadCounters(subscriberId).getOrThrow(),
                packages = roaming.of(subscriberId),
                esims = esims.holdingsOf(subscriberId),
            )
        }
}
