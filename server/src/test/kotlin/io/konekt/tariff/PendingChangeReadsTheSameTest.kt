package io.konekt.tariff

import io.konekt.components.BannerComponent
import io.konekt.components.konektWalk
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.screens.ProfileScreen
import io.konekt.screens.ProfileView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

// TWO SCREENS, ONE WAITING CHANGE, ONE SENTENCE.
//
// The profile and the tariff catalogue both tell a subscriber that a change is waiting, and before
// `B-96` they composed the sentence separately — one of them inside `ProfileRouting`. Two spellings
// of one fact diverge on the edit that touches only one of them, and a copy change is exactly the
// edit nobody thinks to make in two files.
//
// This is the guard for that, and it is deliberately about the RENDERED text rather than about the
// shared function: a screen that stopped calling it and wrote its own would still compile.
class PendingChangeReadsTheSameTest {
    private val effectiveAt = Instant.fromEpochMilliseconds(1_798_761_600_000L)
    private val pending = PendingTariffChange("chg-1", toTariffTitle = "Max", effectiveAt = effectiveAt)

    private fun bannerOn(
        screen: io.github.youndie.kompot.KompotComponent,
        id: String,
    ) = screen
        .konektWalk()
        .filterIsInstance<BannerComponent>()
        .single { it.id == id }
        .text

    @Test
    fun `the profile and the catalogue describe one waiting change in the same words`() {
        val onProfile =
            bannerOn(
                ProfileScreen.build(
                    ProfileView(
                        msisdn = "15550100",
                        esims = EsimHoldings.none,
                        tariffTitle = "Basic",
                        pendingChange = pending,
                    ),
                ),
                "profile-tariff-pending",
            )

        val inCatalogue =
            bannerOn(
                TariffsScreen.build(
                    TariffsView(
                        tariffs = listOf(Tariff("tr-max", "Max", Money.ofMajor(25, Currency.DEFAULT), 51_200)),
                        currentTariffId = "tr-basic",
                        pending = pending,
                    ),
                ),
                "tariffs-pending",
            )

        assertEquals(inCatalogue, onProfile, "two screens describe one waiting change differently")
        // AND THE SENTENCE STILL ANSWERS THE QUESTION. Without this the assertion above is satisfied
        // by two screens that both say nothing — which is what a shared function returning an empty
        // string would produce, and it would look like agreement.
        assertTrue("Max" in onProfile, "the sentence does not name the tariff the change is to: $onProfile")
        assertTrue("Jan" in onProfile, "the sentence does not say when it takes effect: $onProfile")
    }
}
