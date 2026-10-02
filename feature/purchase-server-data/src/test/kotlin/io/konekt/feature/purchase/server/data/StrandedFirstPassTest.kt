package io.konekt.feature.purchase.server.data

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichEngineConfig
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.PetichStepRecord
import io.github.youndie.petich.ResumePayload
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.SuspendedPetichSweeper
import io.github.youndie.petich.postgres.ExposedPetichRepository
import io.github.youndie.petich.postgres.OutboxEventsTable
import io.github.youndie.petich.postgres.PetichTable
import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.purchase.server.domain.ConfirmPurchaseUseCase
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.Entitlements
import io.konekt.feature.purchase.server.domain.Held
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.Provisioned
import io.konekt.feature.purchase.server.domain.PurchaseConfirmation
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.PurchaseRefusals
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.server.domain.purchasePetich
import io.konekt.feature.roaming.server.domain.InMemoryRoamingPackages
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.asPetichClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// THE FIRST PASS, CARRIED FORWARD RATHER THAN ROLLED BACK (B-131).
//
// Since B-130 the sweeper's stranded queue is on: a saga left PROCESSING by a process that died is
// re-driven from the member it died in, by another engine. `ProvisionByOrderTest` covers the member
// after the confirmation; this covers the members before it, which a purchase reaches from its very
// first request. Each test lets the first pass commit its writes and kills the process at the write
// that would have recorded them, then has a second engine's sweeper finish the saga.
//
// The members' writes are the real ones, and `runBlocking` rather than `runTest` for the reason
// PurchaseSagaTest carries.
@OptIn(ExperimentalUuidApi::class)
class StrandedFirstPassTest {
    private class MovableClock(
        private var now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    ) : KonektClock {
        override fun now(): Instant = now

        fun advance(by: Duration) {
            now += by
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
                    polymorphic(PetichStepRecord::class) {
                        subclass(Held::class)
                        subclass(Provisioned::class)
                    }
                }
        }

    // On the test's clock, because the stranded queue asks the store when a row was last written.
    private val repository =
        ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable(), clock.asPetichClock())
    private val balances = ExposedAccountBalances(PostgresHarness.database, clock)
    private val entitlements = ExposedEntitlements(PostgresHarness.database, clock)
    private val counters = ExposedUsageCounters(PostgresHarness.database, clock)
    private val plans = StaticPlanCatalog()

    private val homePlan = "home-20gb-30d"
    private val price = Money.ofMajor(15, Currency.DEFAULT)

    private lateinit var subscriberId: String

    private fun engine(
        repository: OutboxAwarePetichRepository = this.repository,
        balances: AccountBalances = this.balances,
        entitlements: Entitlements = this.entitlements,
    ): PetichEngine =
        PetichEngine(
            definitions =
                listOf(
                    purchasePetich(
                        balances,
                        entitlements,
                        plans,
                        MockPaymentGateway(),
                        counters,
                        InMemoryRoamingPackages { clock.now() },
                        clock,
                        json,
                        5.minutes,
                    ),
                ),
            repository = repository,
            config = PetichEngineConfig(requireOutbox = true),
            clock = clock.asPetichClock(),
        )

    @BeforeTest
    fun reset() {
        PostgresHarness.truncateAll()
    }

    private fun seed(opening: Money) {
        val newSubscriberId = Uuid.random().toString()
        subscriberId = newSubscriberId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550107777"
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

    // THE CASE B-131 WAS OPENED FOR. The hold and the pending entitlement commit, and the process dies
    // at the write that parks the saga at its confirmation. Before the change the second `hold` hit
    // the ledger's unique index, the member threw, and the hold was released by the order: the money
    // came back and the purchase the subscriber started ended `compensated`.
    @Test
    fun `a hold whose process died before the confirmation is carried to it once`() =
        runBlocking {
            val opening = Money.ofMajor(50, Currency.DEFAULT)
            seed(opening)

            val orderId = dieAfterTheEntitlementAndSweep()

            assertWaitsOnceForItsConfirmation(orderId, opening)
            assertCompletesOnce(orderId, opening)
        }

    // THE SAME DEATH ON A BALANCE THAT COVERS THE PRICE ONCE, and the case B-131 did not see. The
    // second `hold` asks the database to take the price again, the WHERE clause refuses, and the
    // member reads that as a balance too low — so the re-run REFUSED the purchase. petich does not
    // undo the member that refuses (it reported its outcome), so the first pass's hold was never
    // returned: the order ended `rejected` with the subscriber's money held under it for good.
    @Test
    fun `a hold that left too little for a second is not refused on its re-run`() =
        runBlocking {
            val opening = Money.ofMajor(20, Currency.DEFAULT)
            seed(opening)

            val orderId = dieAfterTheEntitlementAndSweep()

            assertEquals(0, ledgerEntries(orderId, LedgerEntryTable.DECLINE), "the re-run refused the hold it had already taken")
            assertWaitsOnceForItsConfirmation(orderId, opening)
            assertCompletesOnce(orderId, opening)
        }

    // A REFUSAL IS STILL A REFUSAL AFTER A DEATH. The validation records why it refused, and the
    // process dies at the write that would end the saga. The re-run refuses again and records the
    // same reason under the same order — which was a plain insert under `(order_id, kind)`, so the
    // check threw, and a purchase refused for its balance ended `compensated`: the screen told the
    // subscriber their purchase was rolled back rather than that they could not afford it.
    @Test
    fun `a refusal whose process died is still a refusal`() =
        runBlocking {
            val opening = Money.ofMajor(10, Currency.DEFAULT)
            seed(opening)
            val dying = ProcessDiesAtTheNextWrite(repository)
            val declines = DeclinesThatArm(balances, dying)

            assertFailsWith<ProcessDied> { start(engine(repository = dying, balances = declines)).getOrThrow() }
            val orderId = assertNotNull(dying.died, "the process never died — nothing was tested")
            assertEquals(PetichStatus.PROCESSING, repository.findById(orderId)?.status)
            sweepStranded(declines)

            assertEquals(2, declines.recorded, "the refusal was not run again — nothing here was re-driven")
            val order = assertNotNull(repository.findById(orderId))
            assertEquals(OrderStatus.REJECTED, OrderStatus.of(order.status), "a refusal ended as something else")
            assertEquals(PurchaseRefusals.INSUFFICIENT_FUNDS, balances.declineReason(orderId))
            assertEquals(1, ledgerEntries(orderId, LedgerEntryTable.DECLINE))
            assertEquals(0, ledgerEntries(orderId, LedgerEntryTable.HOLD))
            assertEquals(opening, balance(), "a refused purchase moved money")
        }

    // Starts a purchase whose process dies right after the pending entitlement lands, then has a
    // second engine's sweeper re-drive it. Returns the order id.
    private suspend fun dieAfterTheEntitlementAndSweep(): String {
        val dying = ProcessDiesAtTheNextWrite(repository)
        val holds = CountingHolds(balances)

        assertFailsWith<ProcessDied> {
            start(engine(repository = dying, balances = holds, entitlements = EntitlementsThatArm(entitlements, dying)))
                .getOrThrow()
        }
        val orderId = assertNotNull(dying.died, "the process never died — nothing was tested")
        assertEquals(PetichStatus.PROCESSING, repository.findById(orderId)?.status)
        assertEquals(1, ledgerEntries(orderId, LedgerEntryTable.HOLD), "the first pass did not hold — nothing was tested")

        sweepStranded(holds)

        assertEquals(2, holds.asked, "the hold was not run again — nothing here was re-driven")
        return orderId
    }

    // A second process: its own engine, the real store, the server's own window.
    private suspend fun sweepStranded(balances: AccountBalances = this.balances) {
        val sweeper =
            SuspendedPetichSweeper(
                repository = repository,
                engine = engine(balances = balances),
                clock = clock.asPetichClock(),
                stuckAfter = MockPaymentGateway.STRANDED_AFTER,
            )
        assertEquals(0, sweeper.sweepStuck(), "a saga inside its window was taken as stranded")
        clock.advance(MockPaymentGateway.STRANDED_AFTER + 1.minutes)
        assertEquals(1, sweeper.sweepStuck(), "the stranded queue did not pick the saga up")
    }

    private suspend fun assertWaitsOnceForItsConfirmation(
        orderId: String,
        opening: Money,
    ) {
        val order = assertNotNull(repository.findById(orderId))
        assertEquals(
            OrderStatus.AWAITING_CONFIRMATION,
            OrderStatus.of(order.status),
            "a purchase whose process died was not carried to its confirmation",
        )
        assertEquals(opening - price, balance(), "the subscriber was held other than once")
        assertEquals(1, ledgerEntries(orderId, LedgerEntryTable.HOLD))
        assertEquals(0, ledgerEntries(orderId, LedgerEntryTable.RELEASE), "the hold came back")
        assertEquals(Entitlement.PENDING, entitlements.findByOrder(orderId)?.status)
    }

    // And the purchase goes on to finish, which is the point of carrying it forward.
    private suspend fun assertCompletesOnce(
        orderId: String,
        opening: Money,
    ) {
        val confirmed =
            ConfirmPurchaseUseCase(engine(), repository, balances)(ConfirmPurchaseUseCase.Params(orderId, subscriberId))
                .getOrThrow()
        assertEquals(OrderStatus.COMPLETED, confirmed.status)
        assertEquals(opening - price, balance(), "the subscriber was charged other than once")
        assertEquals(1, ledgerEntries(orderId, LedgerEntryTable.CAPTURE))
        assertEquals(0, ledgerEntries(orderId, LedgerEntryTable.RELEASE))
        assertEquals(Entitlement.ACTIVE, entitlements.findByOrder(orderId)?.status)
        assertEquals(listOf("$orderId:purchase.completed"), outboxIds())
    }

    private suspend fun start(engine: PetichEngine) =
        StartPurchaseUseCase(engine, repository, plans, balances)(StartPurchaseUseCase.Params(subscriberId, homePlan))

    private fun balance(): Money =
        transaction(PostgresHarness.database) {
            AccountTable
                .selectAll()
                .where { AccountTable.subscriberId eq subscriberId }
                .single()
                .let { Money(it[AccountTable.balanceMinor], Currency.valueOf(it[AccountTable.currency].trim())) }
        }

    private fun ledgerEntries(
        orderId: String,
        kind: String,
    ): Int =
        transaction(PostgresHarness.database) {
            LedgerEntryTable
                .selectAll()
                .where { (LedgerEntryTable.orderId eq orderId) and (LedgerEntryTable.kind eq kind) }
                .count()
                .toInt()
        }

    private fun outboxIds(): List<String> =
        transaction(PostgresHarness.database) {
            OutboxEventsTable().let { table -> table.selectAll().map { it[table.id] } }
        }

    // How a process stops, as far as the engine can tell: nothing it catches. petich turns an
    // Exception into a rollback, so an Exception here would be a failure rather than a death.
    private class ProcessDied : Error("the process stopped before the saga was written")

    // The saga store, which dies once: at the first write after it was armed, before that write goes
    // through. `died` is the saga that was being written.
    private class ProcessDiesAtTheNextWrite(
        private val real: OutboxAwarePetichRepository,
    ) : OutboxAwarePetichRepository {
        var armed = false
        var died: String? = null

        override suspend fun findById(id: String): Petich? = real.findById(id)

        override suspend fun saveOrGet(petich: Petich): Petich = real.saveOrGet(petich)

        override suspend fun update(petich: Petich): Boolean = update(petich, emptyList())

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            if (armed && died == null) {
                died = petich.id
                throw ProcessDied()
            }
            return real.update(petich, outboxEvents)
        }
    }

    // The real entitlement write, which arms the store once it has landed: the next write is the one
    // that parks the saga at its confirmation.
    private class EntitlementsThatArm(
        private val real: Entitlements,
        private val store: ProcessDiesAtTheNextWrite,
    ) : Entitlements by real {
        override suspend fun createPending(
            orderId: String,
            subscriberId: String,
            planId: String,
            price: Money,
        ) {
            real.createPending(orderId, subscriberId, planId, price)
            store.armed = true
        }
    }

    // The real hold, counted: two calls are two runs of the member.
    private class CountingHolds(
        private val real: AccountBalances,
    ) : AccountBalances by real {
        var asked = 0

        override suspend fun hold(
            accountId: String,
            orderId: String,
            amount: Money,
        ): Boolean {
            asked++
            return real.hold(accountId, orderId, amount)
        }
    }

    // The real decline, counted, which arms the store once it has landed: the next write is the one
    // that would start the refusal's rollback.
    private class DeclinesThatArm(
        private val real: AccountBalances,
        private val store: ProcessDiesAtTheNextWrite,
    ) : AccountBalances by real {
        var recorded = 0

        override suspend fun recordDecline(
            accountId: String,
            orderId: String,
            amount: Money,
            reason: String,
        ) {
            recorded++
            real.recordDecline(accountId, orderId, amount, reason)
            store.armed = true
        }
    }
}
