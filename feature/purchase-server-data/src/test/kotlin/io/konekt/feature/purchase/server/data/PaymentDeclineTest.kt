package io.konekt.feature.purchase.server.data

import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.ConfirmPurchaseUseCase
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.PurchaseConfirmation
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.asPetichClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichEngineConfig
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.ResumePayload
import ru.workinprogress.petich.SimpleEnrichedPayload
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The branch a provider that always succeeds cannot reach.
//
// `runBlocking` and not `runTest` for the reason PurchaseSagaTest carries: the engine wraps each step
// in `withTimeout`, and a virtual clock cancels the first real suspension inside one. That matters
// twice here, because the delay mode suspends on purpose.
@OptIn(ExperimentalUuidApi::class)
class PaymentDeclineTest {
    private val clock = KonektClock { Instant.fromEpochMilliseconds(1_700_000_000_000) }

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(PurchasePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                    polymorphic(ResumePayload::class) { subclass(PurchaseConfirmation::class) }
                }
        }

    private val repository = ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable())
    private val balances = ExposedAccountBalances(PostgresHarness.database, clock)
    private val entitlements = ExposedEntitlements(PostgresHarness.database, clock)
    private val plans = StaticPlanCatalog()

    // The usage feature's port, real rather than stubbed: a completed purchase grants the
    // plan's allowance, and a double here would hide the one write that makes the home screen
    // show anything at all.
    private val grants = ExposedUsageCounters(PostgresHarness.database, clock)

    private val opening = Money.ofMajor(50, Currency.DEFAULT)
    private val price = Money.ofMajor(12, Currency.DEFAULT)
    private val planId = "tr-10gb-30d"
    private lateinit var subscriberId: String

    private fun sagaWith(payments: MockPaymentGateway): Pair<StartPurchaseUseCase, ConfirmPurchaseUseCase> {
        val engine =
            PetichEngine(
                interceptors = purchaseInterceptors(balances, entitlements, plans, payments, grants, json, 5.minutes),
                repository = repository,
                config = PetichEngineConfig(requireOutbox = true),
                clock = clock.asPetichClock(),
            )
        return StartPurchaseUseCase(engine, repository, plans, balances) to
            ConfirmPurchaseUseCase(engine, repository, balances)
    }

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val newSubscriberId = Uuid.random().toString()
        subscriberId = newSubscriberId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550109999"
                it[createdAt] = 0
            }
            AccountTable.insert {
                it[id] = Uuid.random().toString()
                it[AccountTable.subscriberId] = newSubscriberId
                it[balanceMinor] = opening.minorUnits
                it[currency] = opening.currency.name
                it[createdAt] = 0
            }
        }
    }

    @Test
    fun `a declined provider rolls the purchase back and the screen says why`() =
        runBlocking {
            val (start, confirm) =
                sagaWith(
                    MockPaymentGateway(
                        mode = MockPaymentGateway.Mode.DECLINE,
                        declineReason = "The provider declined the operation.",
                    ),
                )

            val started = start(StartPurchaseUseCase.Params(subscriberId, planId)).getOrThrow()
            assertEquals(OrderStatus.AWAITING_CONFIRMATION, started.status)
            assertEquals(opening - price, balance())

            val settled = confirm(ConfirmPurchaseUseCase.Params(started.orderId, subscriberId)).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, settled.status)
            // The sentence the canvas draws, and the two facts in it: what was reversed and what the
            // balance is now.
            assertEquals("The provider declined the operation.", settled.declineReason)
            assertEquals(opening, balance(), "the hold was not returned")
            assertEquals(Entitlement.CANCELLED, entitlements.findByOrder(started.orderId)?.status)
        }

    @Test
    fun `a slow provider still completes and actually took the time`() =
        runBlocking {
            val (start, confirm) = sagaWith(MockPaymentGateway(delay = 300.milliseconds))

            val started = start(StartPurchaseUseCase.Params(subscriberId, planId)).getOrThrow()

            val mark = TimeSource.Monotonic.markNow()
            val settled = confirm(ConfirmPurchaseUseCase.Params(started.orderId, subscriberId)).getOrThrow()
            val elapsed = mark.elapsedNow()

            // Both halves. Without the second the test passes on a mock that ignores its delay, and
            // the "processing" frame of the canvas would have nothing behind it.
            assertEquals(OrderStatus.COMPLETED, settled.status)
            assertTrue(elapsed >= 300.milliseconds, "the delay was not honoured — took $elapsed")
            assertEquals(opening - price, balance())
        }

    @Test
    fun `a decline leaves the reason where the order can be read again`() =
        runBlocking {
            val (start, confirm) = sagaWith(MockPaymentGateway(mode = MockPaymentGateway.Mode.DECLINE))
            val started = start(StartPurchaseUseCase.Params(subscriberId, planId)).getOrThrow()
            confirm(ConfirmPurchaseUseCase.Params(started.orderId, subscriberId)).getOrThrow()

            // Not only in the response to the confirming request: a subscriber who closed the app and
            // came back must still be told why. petich carries a Compensate reason to its metrics and
            // does not persist one, which is why the step that learned it writes it down.
            val reread = assertNotNull(balances.declineReason(started.orderId))
            assertTrue(reread.isNotBlank())
        }

    private fun balance(): Money =
        transaction(PostgresHarness.database) {
            AccountTable
                .selectAll()
                .where { AccountTable.subscriberId eq subscriberId }
                .single()
                .let { Money(it[AccountTable.balanceMinor], Currency.valueOf(it[AccountTable.currency].trim())) }
        }
}
