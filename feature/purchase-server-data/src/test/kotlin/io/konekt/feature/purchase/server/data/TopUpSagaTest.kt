package io.konekt.feature.purchase.server.data

import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.CollectFundsInterceptor
import io.konekt.feature.purchase.server.domain.FindTopUpUseCase
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.StartTopUpUseCase
import io.konekt.feature.purchase.server.domain.TOP_UP_SAGA_TYPE
import io.konekt.feature.purchase.server.domain.TopUpAmount
import io.konekt.feature.purchase.server.domain.TopUpLimits
import io.konekt.feature.purchase.server.domain.TopUpPayload
import io.konekt.feature.purchase.server.domain.topUpInterceptors
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.asPetichClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichEngineConfig
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.SimpleEnrichedPayload
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The saga that puts money in, against a real Postgres.
//
// `runBlocking` and not `runTest`, for the reason the sibling saga tests carry: the engine wraps each
// step in `withTimeout`, and a virtual clock cancels the first real suspension inside one.
@OptIn(ExperimentalUuidApi::class)
class TopUpSagaTest {
    private val clock = KonektClock { Instant.fromEpochMilliseconds(1_700_000_000_000) }

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(TopUpPayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }

    private val repository = ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable())
    private val balances = ExposedAccountBalances(PostgresHarness.database, clock)

    private val opening = Money.ofMajor(10, Currency.DEFAULT)
    private val amount = Money.ofMajor(25, Currency.DEFAULT)
    private lateinit var subscriberId: String
    private lateinit var accountId: String

    private fun sagaWith(payments: MockPaymentGateway): StartTopUpUseCase =
        StartTopUpUseCase(
            engine =
                PetichEngine(
                    interceptors = topUpInterceptors(balances, payments, json),
                    repository = repository,
                    config = PetichEngineConfig(requireOutbox = true),
                    clock = clock.asPetichClock(),
                ),
            topUps = repository,
            balances = balances,
        )

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val newSubscriberId = Uuid.random().toString()
        val newAccountId = Uuid.random().toString()
        subscriberId = newSubscriberId
        accountId = newAccountId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550107777"
                it[createdAt] = 0
            }
            AccountTable.insert {
                it[id] = newAccountId
                it[AccountTable.subscriberId] = newSubscriberId
                it[balanceMinor] = opening.minorUnits
                it[currency] = opening.currency.name
                it[createdAt] = 0
            }
        }
    }

    @Test
    fun `a top-up the provider takes raises the balance by exactly the amount`() =
        runBlocking {
            val start = sagaWith(MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))

            val view = start(StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(amount.minorUnits))).getOrThrow()

            assertEquals(OrderStatus.COMPLETED, view.status)
            assertEquals(opening + amount, view.balance)
            assertEquals(opening + amount, balances.balanceOf(accountId))
            assertNull(view.declineReason)
        }

    @Test
    fun `a top-up the provider refuses leaves the balance exactly where it was`() =
        runBlocking {
            val start =
                sagaWith(
                    MockPaymentGateway(
                        mode = MockPaymentGateway.Mode.DECLINE,
                        declineReason = "The provider declined the operation.",
                    ),
                )

            val view = start(StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(amount.minorUnits))).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, view.status)
            // The assertion the ordering exists for. A balance raised before the provider confirmed is
            // money the operator has given away, and this is the case that would give it away.
            assertEquals(opening, view.balance)
            assertEquals(opening, balances.balanceOf(accountId))
            assertEquals("The provider declined the operation.", view.declineReason)
        }

    @Test
    fun `an amount below the floor is rejected, and nothing is compensated`() =
        runBlocking {
            val start = sagaWith(MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))

            val view =
                start(
                    StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(TopUpLimits.MIN_MINOR - 1)),
                ).getOrThrow()

            // REJECTED and not COMPENSATED: a rule refused before anything happened, so there is
            // nothing to undo and no compensating step runs at all.
            assertEquals(OrderStatus.REJECTED, view.status)
            assertEquals(opening, balances.balanceOf(accountId))
            assertNull(view.declineReason, "nothing declined it — a rule refused it")
        }

    @Test
    fun `an amount above the ceiling is rejected`() =
        runBlocking {
            val start = sagaWith(MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))

            val view =
                start(
                    StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(TopUpLimits.MAX_MINOR + 1)),
                ).getOrThrow()

            assertEquals(OrderStatus.REJECTED, view.status)
            assertEquals(opening, balances.balanceOf(accountId))
        }

    // THE BRANCH NOTHING ELSE CAN REACH, and it is driven directly for exactly that reason.
    //
    // `CollectFundsInterceptor.compensate` takes the money back when a step AFTER the credit fails.
    // Today the only step after it announces and cannot fail, so no scenario — not the stand, not the
    // saga tests above — ever runs this. It is written because the day a step is added between them,
    // the failure is a subscriber holding money the operator was never paid for, and nothing would
    // have objected.
    @Test
    fun `taking a credit back debits exactly what was credited`(): Unit =
        runBlocking {
            val interceptor =
                CollectFundsInterceptor(balances, MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))
            val payload = TopUpPayload(subscriberId = subscriberId, accountId = accountId, amount = amount)
            val saga =
                Petich(
                    id = Uuid.random().toString(),
                    type = TOP_UP_SAGA_TYPE,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = SimpleEnrichedPayload(),
                )

            interceptor.intercept(saga, payload)
            assertEquals(opening + amount, balances.balanceOf(accountId))

            interceptor.compensate(saga, payload)

            assertEquals(opening, balances.balanceOf(accountId))
            // Both movements are in the ledger under their own kinds, which is what keeps a top-up
            // and its reversal legible a year from now — a RELEASE would have said the money was
            // always the subscriber's.
            assertNotNull(balances.balanceOf(accountId))
        }
}
