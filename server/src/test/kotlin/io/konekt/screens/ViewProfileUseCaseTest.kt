package io.konekt.screens

import io.konekt.domain.KonektException
import io.konekt.domain.Money
import io.konekt.feature.auth.server.domain.Msisdn
import io.konekt.feature.auth.server.domain.Subscriber
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.esim.server.domain.EsimProfile
import io.konekt.feature.esim.server.domain.EsimRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// THE PROFILE SCREEN'S DECISIONS, ASSERTED WITHOUT A TREE — which is what `B-96` bought, and the
// reason this file exists rather than more cases in `ProfileScreenTest`.
//
// IT USED TO BE MOSTLY ABOUT THE TARIFF: which one a subscriber was on, what it was called, and how a
// waiting change was described. `B-102` took all of that off this screen — a tariff nobody chose,
// priced at a rate nothing charges and promising an allowance nothing grants — so those cases are
// gone with the thing they were about, rather than kept as tests of removed behaviour.
//
// What is left is what the profile still answers, and it is worth having: whose line this is, what is
// on it, and the one refusal.
class ViewProfileUseCaseTest {
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

    private fun useCase(holdings: EsimHoldings = EsimHoldings.none) = ViewProfileUseCase(subscribers, esims(holdings))

    // WHAT IS ON THE LINE TRAVELS WHOLE. The split is `EsimHoldings`' and was made once, in the port,
    // because one count answering two questions is what `B-69` was; a view that flattened it here
    // would be that defect again one layer up.
    @Test
    fun `the holdings arrive as the port split them`() =
        runTest {
            val holdings = EsimHoldings(held = 2, awaitingInstall = 1, installed = 1)

            assertEquals(holdings, useCase(holdings).invoke("sub-1").getOrThrow().esims)
        }

    @Test
    fun `the number is the subscriber's own`() =
        runTest {
            assertEquals("15550100", useCase().invoke("sub-1").getOrThrow().msisdn)
        }

    // The one refusal this screen has. A token whose subscriber no longer exists is a 404 and not an
    // empty screen — it cannot happen today, and the alternative is a screen drawing a blank where a
    // number should be.
    @Test
    fun `a token whose subscriber is gone is a not-found rather than an empty screen`() =
        runTest {
            assertFailsWith<KonektException.NotFound> {
                useCase().invoke("sub-missing").getOrThrow()
            }
        }
}
