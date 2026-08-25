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
import org.jetbrains.exposed.v1.jdbc.update
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.ExpiringPetichRepository
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichEngineConfig
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.ResumePayload
import ru.workinprogress.petich.SimpleEnrichedPayload
import ru.workinprogress.petich.SuspendedPetichSweeper
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The saga end to end, against a real Postgres, with the clock in the test's hand.
//
// The two branches this exists for are the confirmed one and the abandoned one, and the second is
// the reason petich is in this build at all: a purchase nobody confirms must leave the balance
// exactly where it was.
// NOT `runTest`, and this cost an hour.
//
// `runTest` runs on a virtual clock: a coroutine that suspends has time skipped forward for it. The
// engine wraps every interceptor in `withTimeout(phaseTimeout)`, so the moment an interceptor
// suspends on real I/O — a database call — the virtual clock jumps past the phase timeout and the
// step is cancelled. The saga then compensates, and what a test sees is a purchase that rolled itself
// back for no reason it can name: no exception in the log, because petich swallows it into a
// compensation.
//
// A test whose subject does real I/O inside somebody else's `withTimeout` needs a real clock.
@OptIn(ExperimentalUuidApi::class)
class PurchaseSagaTest {
    private class MovableClock(
        private var instant: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    ) : KonektClock {
        override fun now(): Instant = instant

        fun advance(by: kotlin.time.Duration) {
            instant += by
        }
    }

    private val clock = MovableClock()

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(PurchasePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                    polymorphic(ResumePayload::class) { subclass(PurchaseConfirmation::class) }
                }
        }

    private val repository =
        ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable())

    private val balances = ExposedAccountBalances(PostgresHarness.database, clock)
    private val entitlements = ExposedEntitlements(PostgresHarness.database, clock)
    private val plans = StaticPlanCatalog()

    private val ttl = 5.minutes

    private val engine =
        PetichEngine(
            interceptors = purchaseInterceptors(balances, entitlements, plans, json, ttl),
            repository = repository,
            config = PetichEngineConfig(requireOutbox = true),
            clock = clock.asPetichClock(),
        )

    private val sweeper =
        SuspendedPetichSweeper(
            repository = repository as ExpiringPetichRepository,
            engineFor = { engine },
            clock = clock.asPetichClock(),
        )

    private val start = StartPurchaseUseCase(engine, repository, plans, balances)
    private val confirm = ConfirmPurchaseUseCase(engine, repository)

    private lateinit var subscriberId: String
    private val opening = Money.ofMajor(50, Currency.DEFAULT)
    private val planId = "tr-10gb-30d"
    private val price = Money.ofMajor(12, Currency.DEFAULT)

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        // A local, and NOT the property, because inside `insert { }` the table is the receiver and
        // `subscriberId` resolves to the COLUMN — Exposed then emits
        // `VALUES (?, account.subscriber_id, ...)` and Postgres refuses it. The same trap the auth
        // repository has a comment about, fallen into here two days later.
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
    fun `a confirmed purchase debits the account, activates the package and completes`() =
        runBlocking {
            val started = start(StartPurchaseUseCase.Params(subscriberId, planId)).getOrThrow()

            // The money is held before the subscriber is asked, which is the point of the order: a
            // confirmation for money that turns out not to be there is a worse screen than a refusal.
            assertEquals(OrderStatus.AWAITING_CONFIRMATION, started.status)
            assertEquals(opening - price, balance())
            assertEquals(Entitlement.PENDING, entitlementStatus(started.orderId))

            val confirmed = confirm(ConfirmPurchaseUseCase.Params(started.orderId, subscriberId)).getOrThrow()

            assertEquals(OrderStatus.COMPLETED, confirmed.status)
            assertEquals(opening - price, balance(), "the balance moved twice")
            assertEquals(Entitlement.ACTIVE, entitlementStatus(started.orderId))
            assertTrue(outboxTypes(started.orderId).contains("purchase.completed"))
        }

    @Test
    fun `a purchase nobody confirms is rolled back and the balance returns`() =
        runBlocking {
            val started = start(StartPurchaseUseCase.Params(subscriberId, planId)).getOrThrow()
            assertEquals(opening - price, balance())

            // Inside the window nothing happens. Asserted, because a sweeper that rolled everything
            // back regardless of time would pass the rest of this test on its own.
            assertEquals(0, sweeper.sweep())

            clock.advance(ttl + 1.minutes)
            assertEquals(1, sweeper.sweep())

            val after = repository.findById(started.orderId)
            assertEquals(OrderStatus.COMPENSATED, OrderStatus.of(assertNotNull(after).status))
            // The sentence the canvas draws, as a number: back to where it was.
            assertEquals(opening, balance())
            assertEquals(Entitlement.CANCELLED, entitlementStatus(started.orderId))
            assertTrue(
                outboxTypes(started.orderId).contains("purchase.reversed"),
                "a consumer that heard 'bought' would never hear that it was undone",
            )
        }

    @Test
    fun `a plan that is not on sale is refused with nothing to undo`() =
        runBlocking {
            val started = start(StartPurchaseUseCase.Params(subscriberId, "us-20gb-30d")).getOrThrow()

            // REJECTED rather than COMPENSATED: the refusal happened in VALIDATION, before the hold,
            // so there is nothing to reverse and the subscriber's balance never moved.
            assertEquals(OrderStatus.REJECTED, started.status)
            assertEquals(opening, balance())
            assertEquals(null, entitlementStatus(started.orderId))
        }

    @Test
    fun `a purchase beyond the balance is refused and the balance is untouched`() =
        runBlocking {
            spend(Money.ofMajor(45, Currency.DEFAULT))

            val started = start(StartPurchaseUseCase.Params(subscriberId, planId)).getOrThrow()

            assertEquals(OrderStatus.REJECTED, started.status)
            assertEquals(Money.ofMajor(5, Currency.DEFAULT), balance())
        }

    @Test
    fun `somebody else's order cannot be confirmed and does not admit to existing`() =
        runBlocking {
            val started = start(StartPurchaseUseCase.Params(subscriberId, planId)).getOrThrow()

            val failure = confirm(ConfirmPurchaseUseCase.Params(started.orderId, "someone-else")).exceptionOrNull()

            // NotFound, not Forbidden. A 403 would confirm that the order exists.
            assertTrue(failure is io.konekt.domain.KonektException.NotFound, "answered $failure")
            assertEquals(opening - price, balance(), "a refused confirmation moved money")
        }

    private fun balance(): Money =
        transaction(PostgresHarness.database) {
            AccountTable
                .selectAll()
                .where { AccountTable.subscriberId eq subscriberId }
                .single()
                .let { Money(it[AccountTable.balanceMinor], Currency.valueOf(it[AccountTable.currency].trim())) }
        }

    private fun spend(amount: Money) =
        transaction(PostgresHarness.database) {
            AccountTable.update({ AccountTable.subscriberId eq subscriberId }) {
                it[balanceMinor] = opening.minorUnits - amount.minorUnits
            }
        }

    private suspend fun entitlementStatus(orderId: String): String? = entitlements.findByOrder(orderId)?.status

    private fun outboxTypes(orderId: String): List<String> =
        transaction(PostgresHarness.database) {
            OutboxEventsTable().let { table ->
                table.selectAll().toList().map { it[table.type] }
            }
        }
}
