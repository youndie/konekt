package io.konekt.feature.purchase.server.data

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.ExpiringPetichRepository
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
import io.konekt.feature.purchase.server.domain.Held
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.PaymentGateway
import io.konekt.feature.purchase.server.domain.Provisioned
import io.konekt.feature.purchase.server.domain.PurchaseConfirmation
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.server.domain.purchasePetich
import io.konekt.feature.roaming.server.domain.InMemoryRoamingPackages
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.asPetichClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// TWO REPLICAS, ONE SAGA, ONE OF THEM DOES THE WORK — with nothing of konekt's in between.
//
// `ClaimedSweep` claimed each expired saga in a table of its own before the sweeper touched it
// (`B-92`), because petich `0.1.0` let two sweepers that both read a saga before either wrote it
// roll it back twice. petich arbitrates both of its queues itself since its B-26: a sweeper claims
// a saga with one write on the saga's own row, and the one whose write loses skips it
// (`onContended`) rather than retrying. These put two sweepers — each with its own engine, which is
// what a second replica is, since the engine's lock is per process — on the same saga against the
// same Postgres, and make both read it before either writes: the window `ClaimedSweep` closed.
//
// THE WINDOW IS FORCED, NOT HOPED FOR. Left to timing, the second sweeper nearly always arrives after
// the first has written, finds the saga no longer waiting, and the test passes without the race ever
// having happened. So each replica's store holds its read until the other has read too, and the
// test asserts the loser was turned away by the claim — `Contended` — which only a sweeper holding a
// stale copy can be.
//
// `runBlocking` rather than `runTest`, for the reason PurchaseSagaTest carries.
@OptIn(ExperimentalUuidApi::class)
class TwoReplicasSweepTest {
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

    private val opening = Money.ofMajor(50, Currency.DEFAULT)
    private val price = Money.ofMajor(15, Currency.DEFAULT)
    private val ttl = 5.minutes

    // Counted across both replicas: the work, as opposed to the outcome the ledger already makes right.
    private val releases = AtomicInteger()
    private val settlements = AtomicInteger()
    private val contended = AtomicInteger()

    private lateinit var subscriberId: String

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val newSubscriberId = Uuid.random().toString()
        subscriberId = newSubscriberId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550105555"
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

    // The expiry queue: a purchase abandoned at its confirmation, past its deadline, seen by both.
    @Test
    fun `two replicas sweeping one expired purchase roll it back once`() =
        runBlocking {
            val orderId = startHome()
            clock.advance(ttl + 1.minutes)

            val bothRead = CountDownLatch(2)
            val expired =
                race(
                    replica(Meet.ON_REREAD, bothRead),
                    replica(Meet.ON_REREAD, bothRead),
                ) { sweep() }

            assertEquals(1, contended.get(), "the second replica was not turned away by the claim — the race never ran")
            assertEquals(1, expired, "the saga was rolled back by other than exactly one replica")
            assertEquals(1, releases.get(), "the hold's undo ran on both replicas")
            assertEquals(OrderStatus.COMPENSATED, status(orderId))
            assertEquals(opening, balance())
            assertEquals(1, ledgerEntries(orderId, LedgerEntryTable.RELEASE))
        }

    // The stranded queue: a confirmation that wrote its start and died before `provision` ran, seen
    // by both after it has gone unwritten for longer than the server waits.
    @Test
    fun `two replicas sweeping one stranded purchase carry it forward once`() =
        runBlocking {
            val orderId = startHome()
            strandAtProvision(orderId)
            clock.advance(MockPaymentGateway.STRANDED_AFTER + 1.minutes)

            val bothRead = CountDownLatch(2)
            val revived =
                race(
                    replica(Meet.ON_STRANDED_QUERY, bothRead),
                    replica(Meet.ON_STRANDED_QUERY, bothRead),
                ) { sweepStuck() }

            assertEquals(1, contended.get(), "the second replica was not turned away by the claim — the race never ran")
            assertEquals(1, revived, "the saga was carried forward by other than exactly one replica")
            assertEquals(1, settlements.get(), "provision ran on both replicas")
            assertEquals(OrderStatus.COMPLETED, status(orderId))
            assertEquals(opening - price, balance())
        }

    // Both sweeps at once, on threads of their own: each replica's store blocks until the other has
    // read, so they must not share one.
    private suspend fun race(
        first: SuspendedPetichSweeper,
        second: SuspendedPetichSweeper,
        pass: suspend SuspendedPetichSweeper.() -> Int,
    ): Int =
        withContext(Dispatchers.IO) {
            listOf(async { first.pass() }, async { second.pass() }).awaitAll().sum()
        }

    private enum class Meet { ON_REREAD, ON_STRANDED_QUERY }

    // One replica: its own store, its own engine with the server's purchase saga, its own sweeper.
    private fun replica(
        meet: Meet,
        bothRead: CountDownLatch,
    ): SuspendedPetichSweeper {
        val store = HoldsItsRead(repository, meet, bothRead)
        val engine =
            PetichEngine(
                definitions =
                    listOf(
                        purchasePetich(
                            CountingReleases(balances, releases),
                            entitlements,
                            plans,
                            CountingSettlements(MockPaymentGateway(), settlements),
                            counters,
                            InMemoryRoamingPackages { clock.now() },
                            clock,
                            json,
                            ttl,
                        ),
                    ),
                repository = store,
                config = PetichEngineConfig(requireOutbox = true),
                clock = clock.asPetichClock(),
            )
        return SuspendedPetichSweeper(
            repository = store,
            engine = engine,
            clock = clock.asPetichClock(),
            stuckAfter = MockPaymentGateway.STRANDED_AFTER,
            onContended = { contended.incrementAndGet() },
        )
    }

    private suspend fun startHome(): String {
        val engine =
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
                            ttl,
                        ),
                    ),
                repository = repository,
                config = PetichEngineConfig(requireOutbox = true),
                clock = clock.asPetichClock(),
            )
        val started =
            StartPurchaseUseCase(engine, repository, plans, balances)(
                StartPurchaseUseCase.Params(subscriberId, "home-20gb-30d"),
            ).getOrThrow()
        assertEquals(OrderStatus.AWAITING_CONFIRMATION, started.status)
        return started.orderId
    }

    // The row a confirmation leaves when its process dies right after the write that starts it
    // (petich B-66): PROCESSING, no deadline, no rollback start, the position where it parked.
    private suspend fun strandAtProvision(orderId: String) {
        val parked = assertNotNull(repository.findById(orderId))
        check(
            repository.update(
                parked.copy(
                    status = PetichStatus.PROCESSING,
                    suspendedUntilEpochMs = null,
                    compensatingFromIndex = null,
                    version = parked.version + 1,
                ),
            ),
        ) { "the stranding write lost a race nobody else is running" }
    }

    private suspend fun status(orderId: String): OrderStatus =
        OrderStatus.of(assertNotNull(repository.findById(orderId)).status)

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

    // A replica's view of the saga table, which holds its first read of the saga until the other
    // replica has made its own — so both act on a copy taken before either wrote. The expiry queue
    // re-reads the saga inside the engine before it claims; the stranded queue claims straight off
    // its query.
    private class HoldsItsRead(
        private val real: ExposedPetichRepository,
        private val meet: Meet,
        private val bothRead: CountDownLatch,
    ) : OutboxAwarePetichRepository,
        ExpiringPetichRepository {
        private var met = false

        private fun meetOnce() {
            if (met) return
            met = true
            bothRead.countDown()
            val other = bothRead.await(10, TimeUnit.SECONDS)
            check(other) { "the other replica never read the saga — the race was not run" }
        }

        override suspend fun findById(id: String): Petich? =
            real.findById(id).also { if (meet == Meet.ON_REREAD && it != null) meetOnce() }

        override suspend fun findStuck(
            status: PetichStatus,
            notTouchedSinceEpochMs: Long,
            limit: Int,
        ): List<Petich> =
            real.findStuck(status, notTouchedSinceEpochMs, limit).also {
                if (meet == Meet.ON_STRANDED_QUERY && it.isNotEmpty()) meetOnce()
            }

        override suspend fun findExpired(
            nowEpochMs: Long,
            limit: Int,
        ): List<Petich> = real.findExpired(nowEpochMs, limit)

        override suspend fun saveOrGet(petich: Petich): Petich = real.saveOrGet(petich)

        override suspend fun update(petich: Petich): Boolean = real.update(petich)

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean = real.update(petich, outboxEvents)
    }

    private class CountingReleases(
        private val real: AccountBalances,
        private val count: AtomicInteger,
    ) : AccountBalances by real {
        override suspend fun release(
            accountId: String,
            orderId: String,
            amount: Money,
        ): Boolean {
            count.incrementAndGet()
            return real.release(accountId, orderId, amount)
        }
    }

    private class CountingSettlements(
        private val real: PaymentGateway,
        private val count: AtomicInteger,
    ) : PaymentGateway {
        override suspend fun settle(
            orderId: String,
            amount: Money,
        ): PaymentGateway.Settlement {
            count.incrementAndGet()
            return real.settle(orderId, amount)
        }
    }
}
