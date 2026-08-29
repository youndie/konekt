package io.konekt.screens

import io.konekt.domain.Currency
import io.konekt.domain.KonektException
import io.konekt.domain.Money
import io.konekt.feature.auth.server.domain.Msisdn
import io.konekt.feature.auth.server.domain.Subscriber
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.server.domain.EsimProfile
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.tariff.PendingTariffChange
import io.konekt.tariff.Tariff
import io.konekt.tariff.TariffCatalogue
import io.konekt.tariff.TariffChangeRecord
import io.konekt.tariff.TariffChangeStatuses
import io.konekt.tariff.TariffChanges
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

// THE PROFILE SCREEN'S DECISIONS, ASSERTED WITHOUT A TREE — which is the whole of what `B-96` buys,
// and the reason this file exists rather than four more cases in `ProfileScreenTest`.
//
// Every one of these questions used to be answerable only by building a component tree, walking it,
// filtering by a string id and reading a sentence out of a banner. Not one of them is a question
// about a component: which tariff a subscriber is on, whether a change is waiting, and what that
// change is called are answers, and they are what this use case now returns.
//
// The distinction the tests below are drawn along: the *title* is decided here, because resolving an
// id against a catalogue is a lookup. The *sentence* is decided in `ProfileScreen`, because it is
// English rather than fact, and it is asserted there.
class ViewProfileUseCaseTest {
    private val standard = Tariff("tr-standard", "Standard", Money.ofMajor(10, Currency.DEFAULT), 10_240)
    private val large = Tariff("tr-large", "Large", Money.ofMajor(20, Currency.DEFAULT), 40_960)

    private val catalogue =
        object : TariffCatalogue {
            override fun find(tariffId: String): Tariff? = all().firstOrNull { it.id == tariffId }

            override fun all(): List<Tariff> = listOf(standard, large)

            override val default: Tariff = standard
        }

    private val subscribers =
        object : SubscriberRepository {
            override suspend fun findById(id: String): Subscriber? =
                if (id == "sub-1") Subscriber(id, Msisdn.parse("+1 555 0100")) else null

            override suspend fun findByMsisdn(msisdn: Msisdn) = TODO("not part of this screen")

            override suspend fun createWithAccount(
                msisdn: Msisdn,
                openingBalance: Money,
            ) = TODO("not part of this screen")
        }

    private fun esims(holdings: EsimHoldings) =
        object : EsimRepository {
            override suspend fun holdingsOf(subscriberId: String): EsimHoldings = holdings

            override suspend fun heldBy(subscriberId: String): EsimProfile? = TODO("not part of this screen")

            override suspend fun create(
                subscriberId: String,
                iccid: String,
                activationCode: String,
            ) = TODO("not part of this screen")

            override suspend fun findById(esimId: String): EsimProfile? = TODO("not part of this screen")

            override suspend fun markInstalled(esimId: String) = TODO("not part of this screen")
        }

    private fun changes(
        current: String?,
        pending: TariffChangeRecord? = null,
    ) = object : TariffChanges {
        override suspend fun currentTariffId(subscriberId: String): String? = current

        override suspend fun pendingOf(subscriberId: String): TariffChangeRecord? = pending

        override suspend fun record(
            changeId: String,
            subscriberId: String,
            fromTariffId: String,
            toTariffId: String,
            effectiveAt: Instant,
        ) = TODO("not part of this screen")

        override suspend fun apply(changeId: String) = TODO("not part of this screen")

        override suspend fun cancel(changeId: String) = TODO("not part of this screen")

        override suspend fun findByChange(changeId: String): TariffChangeRecord? = TODO("not part of this screen")
    }

    private fun useCase(
        current: String?,
        pending: TariffChangeRecord? = null,
        holdings: EsimHoldings = EsimHoldings.none,
    ) = ViewProfileUseCase(subscribers, esims(holdings), catalogue, changes(current, pending))

    private fun pending(
        toTariffId: String,
        effectiveAt: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000),
    ) = TariffChangeRecord(
        changeId = "chg-1",
        subscriberId = "sub-1",
        fromTariffId = standard.id,
        toTariffId = toTariffId,
        status = TariffChangeStatuses.PENDING,
        effectiveAt = effectiveAt,
    )

    // A SUBSCRIBER WHO HAS NEVER CHANGED IS ON THE CATALOGUE'S DEFAULT, and the screen says the
    // default's TITLE. The null here is the ordinary case — the change log is append-only and holds
    // nothing for a line nobody has moved — and it is exactly the case a screen fed a raw id would
    // draw as an empty string.
    @Test
    fun `a line that has never changed is on the default tariff, by name`() =
        runTest {
            val view = useCase(current = null).invoke("sub-1").getOrThrow()

            assertEquals("Standard", view.tariffTitle)
            assertNull(view.pendingChange)
        }

    @Test
    fun `a line that has changed is on what it changed to, by name`() =
        runTest {
            val view = useCase(current = large.id).invoke("sub-1").getOrThrow()

            assertEquals("Large", view.tariffTitle)
        }

    // BOTH TARIFFS ARE RESOLVED, and the pending one is the half a screen could not resolve for
    // itself without the catalogue. `ProfileRouting` used to do this lookup, inline, on the way to
    // composing a sentence — which is how a routing file came to know what a tariff is called.
    @Test
    fun `a waiting change names the tariff it is going to and when`() =
        runTest {
            val at = Instant.fromEpochMilliseconds(1_760_000_000_000)
            val view = useCase(current = standard.id, pending = pending(large.id, at)).invoke("sub-1").getOrThrow()

            assertEquals("Standard", view.tariffTitle, "the current tariff stops being true too early")
            assertEquals(PendingTariffChange("chg-1", "Large", at), view.pendingChange)
        }

    // A TARIFF THE CATALOGUE HAS FORGOTTEN is still what the subscriber is on. Printing the id is
    // wrong and printing nothing is worse: the screen would draw a blank under the word "Tariff",
    // which reads as a failure to load rather than as a catalogue that has moved on.
    @Test
    fun `a tariff the catalogue no longer lists falls back to its id rather than to nothing`() =
        runTest {
            val view =
                useCase(
                    current = "tr-retired",
                    pending = pending("tr-also-retired"),
                ).invoke("sub-1").getOrThrow()

            assertEquals("tr-retired", view.tariffTitle)
            assertEquals("tr-also-retired", view.pendingChange?.toTariffTitle)
        }

    // The one refusal this screen has. A token whose subscriber no longer exists is a 404 and not an
    // empty screen — it cannot happen today, and the alternative is a screen drawing a blank where a
    // number should be.
    @Test
    fun `a token whose subscriber is gone is a not-found rather than an empty screen`() =
        runTest {
            assertFailsWith<KonektException.NotFound> {
                useCase(current = null).invoke("sub-missing").getOrThrow()
            }
        }

    // WHAT IS ON THE LINE TRAVELS WHOLE. The split is `EsimHoldings`' and was made once, in the port,
    // because one count answering two questions is what `B-69` was; a view that flattened it here
    // would be that defect again one layer up.
    @Test
    fun `the holdings arrive as the port split them`() =
        runTest {
            val holdings = EsimHoldings(held = 2, awaitingInstall = 1, installed = 1)
            val view = useCase(current = null, holdings = holdings).invoke("sub-1").getOrThrow()

            assertEquals(holdings, view.esims)
        }
}
